/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */

#include <ArcReferenceUpdateExpansion.h>

#include <array>
#include <cstdint>
#include <vector>

#include <llvm/IR/Attributes.h>
#include <llvm/IR/Constants.h>
#include <llvm/IR/Function.h>
#include <llvm/IR/IRBuilder.h>
#include <llvm/IR/Instructions.h>
#include <llvm/IR/Module.h>
#include <llvm/Support/CBindingWrapping.h>

using namespace llvm;

namespace {

constexpr const char* kUpdateStack = "UpdateStackRefRelaxed";
constexpr const char* kUpdateReturn = "UpdateReturnRefRelaxed";
constexpr const char* kUpdateHeap = "UpdateHeapRefRelaxed";
constexpr const char* kRuntimeContract =
    "ArcReferenceUpdateExpansionContractV1";
constexpr uint64_t kRuntimeContractValue = 0x4B415201;
constexpr const char* kStackShell = "__konan_arc_self_guard_update_stack_ref_v1";
constexpr const char* kReturnShell = "__konan_arc_self_guard_update_return_ref_v1";
constexpr const char* kHeapShell = "__konan_arc_self_guard_update_heap_ref_v1";

enum class UpdateKind { kStack, kReturn, kHeap };

struct Candidate {
  CallInst* call;
  UpdateKind kind;
};

bool isExactUpdateType(FunctionType* type) {
  if (!type || !type->getReturnType()->isVoidTy() || type->isVarArg() ||
      type->getNumParams() != 2) {
    return false;
  }
  auto* slot = dyn_cast<PointerType>(type->getParamType(0));
  auto* object = dyn_cast<PointerType>(type->getParamType(1));
  return slot && object && slot->getElementType() == object;
}

bool isExactRuntimeContract(Function* function) {
  if (!function || function->isDeclaration()) return false;
  FunctionType* type = function->getFunctionType();
  if (!type->getReturnType()->isIntegerTy(32) || type->isVarArg() ||
      type->getNumParams() != 0 ||
      !function->hasFnAttribute(Attribute::NoUnwind) ||
      function->size() != 1 || function->getEntryBlock().size() != 1) {
    return false;
  }
  auto* result = dyn_cast<ReturnInst>(function->getEntryBlock().getTerminator());
  auto* value = result ? dyn_cast_or_null<ConstantInt>(result->getReturnValue()) : nullptr;
  return value && value->equalsInt(kRuntimeContractValue);
}

bool gatherCandidates(Function* function, UpdateKind kind,
                      std::vector<Candidate>& candidates) {
  for (Use& use : function->uses()) {
    auto* call = dyn_cast<CallInst>(use.getUser());
    if (!call || call->getCalledOperand()->stripPointerCasts() != function ||
        call->getFunctionType() != function->getFunctionType() ||
        call->hasOperandBundles()) {
      return false;
    }
    candidates.push_back({call, kind});
  }
  return true;
}

Function* createShell(Module& module, StringRef name, Function* update,
                      Function* original) {
  Function* shell = Function::Create(update->getFunctionType(),
                                     GlobalValue::InternalLinkage, name, module);
  shell->setCallingConv(update->getCallingConv());
  shell->addFnAttr(Attribute::AlwaysInline);
  shell->addFnAttr(Attribute::NoUnwind);

  auto argument = shell->arg_begin();
  Value* slot = &*argument++;
  Value* value = &*argument;
  slot->setName("slot");
  value->setName("value");
  Type* objectType = value->getType();

  BasicBlock* entry = BasicBlock::Create(module.getContext(), "entry", shell);
  BasicBlock* changed = BasicBlock::Create(module.getContext(), "changed", shell);
  BasicBlock* exit = BasicBlock::Create(module.getContext(), "exit", shell);

  IRBuilder<> builder(entry);
  LoadInst* old = builder.CreateLoad(objectType, slot, "old");
  Value* same = builder.CreateICmpEQ(old, value, "same");
  builder.CreateCondBr(same, exit, changed);

  builder.SetInsertPoint(changed);
  CallInst* originalCall = builder.CreateCall(original, {slot, value});
  originalCall->setCallingConv(original->getCallingConv());
  originalCall->setDoesNotThrow();
  builder.CreateBr(exit);

  builder.SetInsertPoint(exit);
  builder.CreateRetVoid();
  return shell;
}

} // namespace

LLVMKotlinArcReferenceUpdateExpansionStats LLVMKotlinExpandArcReferenceUpdates(
    LLVMModuleRef moduleRef) {
  LLVMKotlinArcReferenceUpdateExpansionStats stats{};
  if (!moduleRef) {
    stats.rejected = 1;
    return stats;
  }
  Module& module = *unwrap(moduleRef);
  std::array<Function*, 3> updates = {
      module.getFunction(kUpdateStack), module.getFunction(kUpdateReturn),
      module.getFunction(kUpdateHeap)};
  if (!updates[0] || !updates[1] || !updates[2] ||
      updates[0]->isDeclaration() || updates[1]->isDeclaration() ||
      updates[2]->isDeclaration() ||
      !isExactUpdateType(updates[0]->getFunctionType()) ||
      updates[1]->getFunctionType() != updates[0]->getFunctionType() ||
      updates[2]->getFunctionType() != updates[0]->getFunctionType() ||
      updates[1]->getCallingConv() != updates[0]->getCallingConv() ||
      updates[2]->getCallingConv() != updates[0]->getCallingConv() ||
      !updates[0]->hasFnAttribute(Attribute::NoUnwind) ||
      !updates[1]->hasFnAttribute(Attribute::NoUnwind) ||
      !updates[2]->hasFnAttribute(Attribute::NoUnwind)) {
    stats.missingRuntime = 1;
    return stats;
  }

  Function* runtimeContract = module.getFunction(kRuntimeContract);
  if (!isExactRuntimeContract(runtimeContract)) {
    stats.missingRuntime = 1;
    return stats;
  }

  std::vector<Candidate> candidates;
  bool valid = gatherCandidates(updates[0], UpdateKind::kStack, candidates) &&
               gatherCandidates(updates[1], UpdateKind::kReturn, candidates) &&
               gatherCandidates(updates[2], UpdateKind::kHeap, candidates);
  stats.candidates = static_cast<int>(candidates.size());
  if (!valid || module.getNamedValue(kStackShell) ||
      module.getNamedValue(kReturnShell) ||
      module.getNamedValue(kHeapShell)) {
    stats.rejected = stats.candidates > 0 ? stats.candidates : 1;
    return stats;
  }
  if (candidates.empty()) return stats;

  // Freeze the candidate set before creating these shells. Each changed path
  // must call the exact original barrier and must never be retargeted itself.
  std::array<Function*, 3> shells = {
      createShell(module, kStackShell, updates[0], updates[0]),
      createShell(module, kReturnShell, updates[1], updates[1]),
      createShell(module, kHeapShell, updates[2], updates[2]),
  };

  for (const Candidate& candidate : candidates) {
    Function* shell = shells[static_cast<size_t>(candidate.kind)];
    candidate.call->setCalledFunction(shell);
    candidate.call->setCallingConv(shell->getCallingConv());
    ++stats.expanded;
  }
  return stats;
}
