/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */

#include <ArcReferenceUpdateExpansion.h>

#include <cstdlib>
#include <memory>
#include <string>

#include <llvm/AsmParser/Parser.h>
#include <llvm/IR/Function.h>
#include <llvm/IR/Instructions.h>
#include <llvm/IR/LLVMContext.h>
#include <llvm/IR/Module.h>
#include <llvm/IR/Verifier.h>
#include <llvm/Support/CBindingWrapping.h>
#include <llvm/Support/SourceMgr.h>
#include <llvm/Support/raw_ostream.h>

using namespace llvm;

namespace {

[[noreturn]] void fail(const std::string& message) {
  errs() << "ArcReferenceUpdateExpansionTest failure: " << message << '\n';
  std::exit(1);
}

void require(bool condition, const char* message) {
  if (!condition) fail(message);
}

std::unique_ptr<Module> parse(LLVMContext& context, const char* text) {
  SMDiagnostic diagnostic;
  auto module = parseAssemblyString(text, diagnostic, context);
  if (!module) {
    diagnostic.print("ArcReferenceUpdateExpansionTest", errs());
    fail("fixture did not parse");
  }
  return module;
}

size_t directCalls(const Function& function, StringRef callee) {
  size_t result = 0;
  for (const BasicBlock& block : function)
    for (const Instruction& instruction : block)
      if (const auto* call = dyn_cast<CallBase>(&instruction))
        if (call->getCalledFunction() && call->getCalledFunction()->getName() == callee) ++result;
  return result;
}

BasicBlock& block(Function& function, StringRef name) {
  for (BasicBlock& candidate : function)
    if (candidate.getName() == name) return candidate;
  fail("missing block " + name.str());
}

CallInst& directCall(BasicBlock& block, StringRef callee) {
  CallInst* result = nullptr;
  for (Instruction& instruction : block) {
    auto* call = dyn_cast<CallInst>(&instruction);
    if (call && call->getCalledFunction() &&
        call->getCalledFunction()->getName() == callee) {
      require(result == nullptr, "duplicate direct call in shell block");
      result = call;
    }
  }
  if (!result) fail("missing direct call " + callee.str());
  return *result;
}

void requireConditionalSuccessors(BasicBlock& source, BasicBlock& onTrue,
                                  BasicBlock& onFalse,
                                  const char* message) {
  auto* branch = dyn_cast<BranchInst>(source.getTerminator());
  require(branch && branch->isConditional() &&
              branch->getSuccessor(0) == &onTrue &&
              branch->getSuccessor(1) == &onFalse,
          message);
}

void verifyShellStructure(Function& shell, StringRef originalName) {
  BasicBlock& entry = block(shell, "entry");
  BasicBlock& changed = block(shell, "changed");
  BasicBlock& exit = block(shell, "exit");

  auto* old = dyn_cast<LoadInst>(&entry.front());
  auto* same = old ? dyn_cast<ICmpInst>(old->getNextNode()) : nullptr;
  require(old && old->getPointerOperand() == shell.getArg(0) && same &&
              same->getPredicate() == ICmpInst::ICMP_EQ &&
              same->getOperand(0) == old &&
              same->getOperand(1) == shell.getArg(1),
          "shell does not load old and test self-assignment first");
  requireConditionalSuccessors(entry, exit, changed,
                               "self-assignment does not exit immediately");

  CallInst& originalCall = directCall(changed, originalName);
  require(originalCall.arg_size() == 2 &&
              originalCall.getArgOperand(0) == shell.getArg(0) &&
              originalCall.getArgOperand(1) == shell.getArg(1) &&
              cast<BranchInst>(changed.getTerminator())->getSuccessor(0) == &exit,
          "changed path does not call the exact original barrier once");
  require(exit.size() == 1 && isa<ReturnInst>(exit.front()),
          "self-assignment exit contains unexpected work");
}

std::string moduleText(const Module& module) {
  std::string result;
  raw_string_ostream stream(result);
  module.print(stream, nullptr);
  stream.flush();
  return result;
}

void requireRejectedUnmodified(LLVMContext& context, const char* text,
                               const char* message) {
  auto module = parse(context, text);
  std::string before = moduleText(*module);
  auto stats = LLVMKotlinExpandArcReferenceUpdates(wrap(module.get()));
  require(stats.expanded == 0 && stats.rejected > 0, message);
  require(moduleText(*module) == before,
          "rejected module was partially mutated");
}

constexpr const char* kValidModule = R"IR(
target datalayout = "e-p:64:64"

define i32 @ArcReferenceUpdateExpansionContractV1() nounwind { ret i32 1262572033 }

define void @UpdateStackRefRelaxed(i8** %slot, i8* %value) nounwind { ret void }
define void @UpdateReturnRefRelaxed(i8** %slot, i8* %value) nounwind { ret void }
define void @UpdateHeapRefRelaxed(i8** %slot, i8* %value) nounwind { ret void }

define void @caller(i8** %stack, i8** %result, i8** %heap, i8* %value) {
entry:
  call void @UpdateStackRefRelaxed(i8** %stack, i8* %value)
  call void @UpdateReturnRefRelaxed(i8** %result, i8* %value)
  call void @UpdateHeapRefRelaxed(i8** %heap, i8* %value)
  ret void
}
)IR";

} // namespace

int main() {
  LLVMContext context;
  auto module = parse(context, kValidModule);
  auto stats = LLVMKotlinExpandArcReferenceUpdates(wrap(module.get()));
  require(stats.candidates == 3 && stats.expanded == 3 && stats.rejected == 0 &&
              stats.missingRuntime == 0,
          "valid module was not fully expanded");
  require(!verifyModule(*module, &errs()), "expanded module is invalid");

  Function* stackShell = module->getFunction("__konan_arc_self_guard_update_stack_ref_v1");
  Function* returnShell = module->getFunction("__konan_arc_self_guard_update_return_ref_v1");
  Function* heapShell = module->getFunction("__konan_arc_self_guard_update_heap_ref_v1");
  require(stackShell && returnShell && heapShell, "self-guard shells were not created");
  require(stackShell->hasFnAttribute(Attribute::AlwaysInline) &&
              stackShell->hasFnAttribute(Attribute::NoUnwind) &&
              returnShell->hasFnAttribute(Attribute::AlwaysInline) &&
              heapShell->hasFnAttribute(Attribute::AlwaysInline),
          "self-guard shells do not have the required attributes");
  verifyShellStructure(*stackShell, "UpdateStackRefRelaxed");
  verifyShellStructure(*returnShell, "UpdateReturnRefRelaxed");
  verifyShellStructure(*heapShell, "UpdateHeapRefRelaxed");
  require(directCalls(*module->getFunction("caller"), "UpdateStackRefRelaxed") == 0 &&
              directCalls(*module->getFunction("caller"), "UpdateReturnRefRelaxed") == 0 &&
              directCalls(*module->getFunction("caller"), "UpdateHeapRefRelaxed") == 0 &&
              directCalls(*module->getFunction("caller"), stackShell->getName()) == 1 &&
              directCalls(*module->getFunction("caller"), returnShell->getName()) == 1 &&
              directCalls(*module->getFunction("caller"), heapShell->getName()) == 1,
          "call sites were not retargeted exactly");

  // One unsupported use rejects the entire module before helpers, attributes,
  // or call targets are changed.
  requireRejectedUnmodified(context, R"IR(
target datalayout = "e-p:64:64"
define i32 @ArcReferenceUpdateExpansionContractV1() nounwind { ret i32 1262572033 }
define void @UpdateStackRefRelaxed(i8** %slot, i8* %value) nounwind { ret void }
define void @UpdateReturnRefRelaxed(i8** %slot, i8* %value) nounwind { ret void }
define void @UpdateHeapRefRelaxed(i8** %slot, i8* %value) nounwind { ret void }
define void @caller(i8** %slot, i8* %value) {
  call void @UpdateStackRefRelaxed(i8** %slot, i8* %value)
  call void @UpdateReturnRefRelaxed(i8** %slot, i8* %value) [ "deopt"(i32 0) ]
  ret void
}
)IR", "operand-bundle call did not fail closed");

  requireRejectedUnmodified(context, R"IR(
target datalayout = "e-p:64:64"
define i32 @ArcReferenceUpdateExpansionContractV1() nounwind { ret i32 1262572033 }
define void @UpdateStackRefRelaxed(i8** %slot, i8* %value) nounwind { ret void }
define void @UpdateReturnRefRelaxed(i8** %slot, i8* %value) nounwind { ret void }
define void @UpdateHeapRefRelaxed(i8** %slot, i8* %value) nounwind { ret void }
define void @caller(i8** %slot, i8* %value) {
  %cast = bitcast void (i8**, i8*)* @UpdateReturnRefRelaxed to void (i8**, ...)*
  call void (i8**, ...) %cast(i8** %slot, i8* %value)
  ret void
}
)IR", "bitcast call did not fail closed");

  requireRejectedUnmodified(context, R"IR(
target datalayout = "e-p:64:64"
define i32 @ArcReferenceUpdateExpansionContractV1() nounwind { ret i32 1262572033 }
define void @UpdateStackRefRelaxed(i8** %slot, i8* %value) nounwind { ret void }
define void @UpdateReturnRefRelaxed(i8** %slot, i8* %value) nounwind { ret void }
define void @UpdateHeapRefRelaxed(i8** %slot, i8* %value) nounwind { ret void }
define void @caller(void (i8**, i8*)** %sink) {
  store void (i8**, i8*)* @UpdateHeapRefRelaxed, void (i8**, i8*)** %sink
  ret void
}
)IR", "indirect function use did not fail closed");

  auto missing = parse(context, R"IR(
target datalayout = "e-p:64:64"
define void @UpdateStackRefRelaxed(i8** %slot, i8* %value) nounwind { ret void }
define void @UpdateReturnRefRelaxed(i8** %slot, i8* %value) nounwind { ret void }
define void @UpdateHeapRefRelaxed(i8** %slot, i8* %value) nounwind { ret void }
define void @caller(i8** %slot, i8* %value) {
  call void @UpdateHeapRefRelaxed(i8** %slot, i8* %value)
  ret void
}
)IR");
  auto missingStats = LLVMKotlinExpandArcReferenceUpdates(wrap(missing.get()));
  require(missingStats.expanded == 0 && missingStats.missingRuntime == 1,
          "missing runtime contract was not rejected");
  require(!missing->getFunction("__konan_arc_self_guard_update_heap_ref_v1"),
          "missing-runtime module was mutated");

  auto wrongContract = parse(context, R"IR(
target datalayout = "e-p:64:64"
define i32 @ArcReferenceUpdateExpansionContractV1() nounwind { ret i32 1262572032 }
define void @UpdateStackRefRelaxed(i8** %slot, i8* %value) nounwind { ret void }
define void @UpdateReturnRefRelaxed(i8** %slot, i8* %value) nounwind { ret void }
define void @UpdateHeapRefRelaxed(i8** %slot, i8* %value) nounwind { ret void }
define void @caller(i8** %slot, i8* %value) {
  call void @UpdateHeapRefRelaxed(i8** %slot, i8* %value)
  ret void
}
)IR");
  std::string wrongContractBefore = moduleText(*wrongContract);
  auto wrongContractStats =
      LLVMKotlinExpandArcReferenceUpdates(wrap(wrongContract.get()));
  require(wrongContractStats.expanded == 0 &&
              wrongContractStats.missingRuntime == 1,
          "wrong expansion runtime contract was not rejected");
  require(moduleText(*wrongContract) == wrongContractBefore,
          "wrong-contract module was partially mutated");

  auto traced = parse(context, R"IR(
target datalayout = "e-p:64:64"
define void @UpdateStackRefRelaxed(i8** %slot, i8* %value) nounwind { ret void }
define void @UpdateReturnRefRelaxed(i8** %slot, i8* %value) nounwind { ret void }
define void @UpdateHeapRefRelaxed(i8** %slot, i8* %value) nounwind { ret void }
define void @caller(i8** %slot, i8* %value) {
  call void @UpdateHeapRefRelaxed(i8** %slot, i8* %value)
  ret void
}
)IR");
  std::string tracedBefore = moduleText(*traced);
  auto tracedStats = LLVMKotlinExpandArcReferenceUpdates(wrap(traced.get()));
  require(tracedStats.expanded == 0 && tracedStats.missingRuntime == 1,
          "instrumented runtime without safety marker was not rejected");
  require(moduleText(*traced) == tracedBefore,
          "instrumented runtime was partially mutated");

  outs() << "ArcReferenceUpdateExpansionTest: OK\n";
  return 0;
}
