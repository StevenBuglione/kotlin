/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */

#include <ArcSelectiveInlining.h>

#include <functional>
#include <memory>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#include <llvm/IR/Constants.h>
#include <llvm/IR/Function.h>
#include <llvm/IR/GlobalAlias.h>
#include <llvm/IR/GlobalVariable.h>
#include <llvm/IR/Instructions.h>
#include <llvm/IR/Module.h>
#include <llvm/Support/CBindingWrapping.h>
#include <llvm/Transforms/Utils/Cloning.h>

using namespace llvm;

namespace {

size_t instructionCount(const Function& function) {
  size_t result = 0;
  for (const BasicBlock& block : function) result += block.size();
  return result;
}

void collectLocalDefinitionClosure(GlobalValue& root,
                                   std::unordered_set<const GlobalValue*>& definitions) {
  std::unordered_set<const Value*> visited;
  std::function<void(const Value*)> visit = [&](const Value* value) {
    if (value == nullptr || !visited.insert(value).second) return;
    if (const auto* global = dyn_cast<GlobalValue>(value)) {
      const bool isRoot = global == &root;
      if (isRoot || (global->hasLocalLinkage() && !global->isDeclaration())) {
        definitions.insert(global);
      } else {
        return;
      }
    }
    if (const auto* user = dyn_cast<User>(value)) {
      for (const Use& operand : user->operands()) visit(operand.get());
    }
    if (const auto* function = dyn_cast<Function>(value)) {
      if (!definitions.count(function)) return;
      for (const BasicBlock& block : *function)
        for (const Instruction& instruction : block) visit(&instruction);
    } else if (const auto* global = dyn_cast<GlobalVariable>(value)) {
      if (definitions.count(global) && global->hasInitializer()) visit(global->getInitializer());
    } else if (const auto* alias = dyn_cast<GlobalAlias>(value)) {
      if (definitions.count(alias)) visit(alias->getAliasee());
    }
  };
  visit(&root);
}

} // namespace

LLVMModuleRef LLVMKotlinCreateArcSelectiveInlineCompanion(
    LLVMModuleRef sourceRef, const char* originalName, const char* cloneName) {
  if (!sourceRef || !originalName || !cloneName) return nullptr;
  Module* source = unwrap(sourceRef);
  Function* original = source->getFunction(originalName);
  if (!original || original->isDeclaration() || original->isVarArg()) return nullptr;

  std::unordered_set<const GlobalValue*> definitions;
  collectLocalDefinitionClosure(*original, definitions);
  ValueToValueMapTy valueMap;
  std::unique_ptr<Module> companion = CloneModule(
      *source, valueMap,
      [&](const GlobalValue* value) { return definitions.count(value) != 0; });
  Function* clone = companion->getFunction(originalName);
  if (!clone || clone->isDeclaration()) return nullptr;
  if (companion->getNamedValue(cloneName) != nullptr) return nullptr;
  clone->setName(cloneName);
  // A local unreferenced definition is discarded by LLVMLinkModules2 before
  // the post-link pass can consume it. Keep the versioned companion root as a
  // hidden definition on disk, then internalize it immediately after linking.
  clone->setLinkage(GlobalValue::ExternalLinkage);
  clone->setVisibility(GlobalValue::HiddenVisibility);
  return wrap(companion.release());
}

int LLVMKotlinCountArcTaggedCalls(LLVMModuleRef moduleRef, const char* metadataName) {
  if (!moduleRef || !metadataName) return 0;
  Module& module = *unwrap(moduleRef);
  const unsigned metadataKind = module.getContext().getMDKindID(metadataName);
  int result = 0;
  for (Function& function : module)
    for (BasicBlock& block : function)
      for (Instruction& instruction : block)
        if (auto* call = dyn_cast<CallBase>(&instruction))
          if (call->getMetadata(metadataKind)) ++result;
  return result;
}

LLVMKotlinArcSelectiveInlineStats LLVMKotlinInlineArcTaggedCalls(
    LLVMModuleRef moduleRef, const char* metadataName, const char* originalName,
    const char* cloneName, int maxInstructionsPerCallee,
    int maxInlineInstructionsPerCaller, int maxInlineInstructionsPerModule) {
  LLVMKotlinArcSelectiveInlineStats stats{};
  if (!moduleRef || !metadataName || !originalName || !cloneName ||
      maxInstructionsPerCallee <= 0 || maxInlineInstructionsPerCaller <= 0 ||
      maxInlineInstructionsPerModule <= 0) {
    stats.rejected = 1;
    return stats;
  }
  Module& module = *unwrap(moduleRef);
  Function* original = module.getFunction(originalName);
  Function* clone = module.getFunction(cloneName);
  const unsigned metadataKind = module.getContext().getMDKindID(metadataName);

  std::vector<CallBase*> tagged;
  for (Function& function : module)
    for (BasicBlock& block : function)
      for (Instruction& instruction : block)
        if (auto* call = dyn_cast<CallBase>(&instruction))
          if (call->getMetadata(metadataKind)) tagged.push_back(call);
  stats.tagged = static_cast<int>(tagged.size());
  if (tagged.empty()) return stats;
  // In no-cache builds the canonical body is already linked. Inline it
  // directly: cloning it adds no isolation and has historically exercised an
  // LLVM 11 invoke-cloning pathology. Cached builds use the companion clone.
  if (original && !clone && !original->isDeclaration()) clone = original;
  if (!original || !clone || clone->isDeclaration()) {
    stats.missingBody = stats.tagged;
    return stats;
  }
  if (clone != original) {
    clone->setLinkage(GlobalValue::InternalLinkage);
    clone->setVisibility(GlobalValue::DefaultVisibility);
  }
  const size_t calleeInstructions = instructionCount(*clone);
  if (calleeInstructions == 0 || calleeInstructions > static_cast<size_t>(maxInstructionsPerCallee) ||
      original->getFunctionType() != clone->getFunctionType()) {
    stats.rejected = stats.tagged;
    return stats;
  }

  std::unordered_map<Function*, size_t> callerBudget;
  size_t moduleBudget = 0;
  for (CallBase* call : tagged) {
    if (!call->getParent() || call->getCalledFunction() != original ||
        call->getFunctionType() != clone->getFunctionType()) {
      ++stats.rejected;
      continue;
    }
    Function* caller = call->getFunction();
    if (callerBudget[caller] + calleeInstructions > static_cast<size_t>(maxInlineInstructionsPerCaller) ||
        moduleBudget + calleeInstructions > static_cast<size_t>(maxInlineInstructionsPerModule)) {
      ++stats.budgetSkipped;
      continue;
    }
    call->setCalledFunction(clone);
    InlineFunctionInfo info;
    if (InlineFunction(*call, info).isSuccess()) {
      callerBudget[caller] += calleeInstructions;
      moduleBudget += calleeInstructions;
      ++stats.inlined;
    } else {
      call->setCalledFunction(original);
      ++stats.inlineFailures;
    }
  }
  return stats;
}
