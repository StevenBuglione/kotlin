/*
 * Copyright 2010-2026 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

#include <ArcReturnUpdateCoalescing.h>

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

constexpr const char* kModule = R"IR(
declare void @UpdateReturnRefRelaxed(i8**, i8*)
declare void @llvm.lifetime.end.p0i8(i64 immarg, i8* nocapture)
declare void @llvm.dbg.value(metadata, metadata, metadata)
declare void @effect(i8*)
declare i32 @__gxx_personality_v0(...)

define void @adjacent(i8** %slot, i8* %value) {
entry:
  call void @UpdateReturnRefRelaxed(i8** %slot, i8* %value)
  call void @UpdateReturnRefRelaxed(i8** %slot, i8* %value)
  ret void
}

define void @run(i8** %slot, i8* %value) {
entry:
  call void @UpdateReturnRefRelaxed(i8** %slot, i8* %value)
  call void @UpdateReturnRefRelaxed(i8** %slot, i8* %value)
  call void @UpdateReturnRefRelaxed(i8** %slot, i8* %value)
  ret void
}

define void @differentSlot(i8** %first, i8** %second, i8* %value) {
entry:
  call void @UpdateReturnRefRelaxed(i8** %first, i8* %value)
  call void @UpdateReturnRefRelaxed(i8** %second, i8* %value)
  ret void
}

define void @differentValue(i8** %slot, i8* %first, i8* %second) {
entry:
  call void @UpdateReturnRefRelaxed(i8** %slot, i8* %first)
  call void @UpdateReturnRefRelaxed(i8** %slot, i8* %second)
  ret void
}

define void @intervening(i8** %slot, i8* %value) {
entry:
  call void @UpdateReturnRefRelaxed(i8** %slot, i8* %value)
  %used = icmp eq i8* %value, null
  call void @UpdateReturnRefRelaxed(i8** %slot, i8* %value)
  ret void
}

define void @lifetimeSeparator(i8** %slot, i8* %value) {
entry:
  call void @UpdateReturnRefRelaxed(i8** %slot, i8* %value)
  call void @llvm.lifetime.end.p0i8(i64 8, i8* %value)
  call void @UpdateReturnRefRelaxed(i8** %slot, i8* %value)
  ret void
}

define void @debugSeparator(i8** %slot, i8* %value) !dbg !4 {
entry:
  call void @UpdateReturnRefRelaxed(i8** %slot, i8* %value)
  call void @llvm.dbg.value(metadata i8* %value, metadata !8, metadata !DIExpression()), !dbg !9
  call void @UpdateReturnRefRelaxed(i8** %slot, i8* %value)
  ret void, !dbg !9
}

define void @callSeparator(i8** %slot, i8* %value) {
entry:
  call void @UpdateReturnRefRelaxed(i8** %slot, i8* %value)
  call void @effect(i8* %value)
  call void @UpdateReturnRefRelaxed(i8** %slot, i8* %value)
  ret void
}

define void @storeSeparator(i8** %slot, i8* %value, i8** %other) {
entry:
  call void @UpdateReturnRefRelaxed(i8** %slot, i8* %value)
  store i8* %value, i8** %other
  call void @UpdateReturnRefRelaxed(i8** %slot, i8* %value)
  ret void
}

define void @loadSeparator(i8** %slot, i8* %value, i8** %other) {
entry:
  call void @UpdateReturnRefRelaxed(i8** %slot, i8* %value)
  %loaded = load i8*, i8** %other
  call void @UpdateReturnRefRelaxed(i8** %slot, i8* %value)
  ret void
}

define void @operandBundle(i8** %slot, i8* %value) {
entry:
  call void @UpdateReturnRefRelaxed(i8** %slot, i8* %value) [ "deopt"(i32 0) ]
  call void @UpdateReturnRefRelaxed(i8** %slot, i8* %value) [ "deopt"(i32 0) ]
  ret void
}

define void @convergentCalls(i8** %slot, i8* %value) {
entry:
  call void @UpdateReturnRefRelaxed(i8** %slot, i8* %value) #0
  call void @UpdateReturnRefRelaxed(i8** %slot, i8* %value) #0
  ret void
}

define void @noduplicateCalls(i8** %slot, i8* %value) {
entry:
  call void @UpdateReturnRefRelaxed(i8** %slot, i8* %value) #1
  call void @UpdateReturnRefRelaxed(i8** %slot, i8* %value) #1
  ret void
}

define void @invokeUpdates(i8** %slot, i8* %value) personality i32 (...)* @__gxx_personality_v0 {
entry:
  invoke void @UpdateReturnRefRelaxed(i8** %slot, i8* %value)
          to label %second unwind label %cleanup
second:
  invoke void @UpdateReturnRefRelaxed(i8** %slot, i8* %value)
          to label %done unwind label %cleanup
done:
  ret void
cleanup:
  %exception = landingpad { i8*, i32 } cleanup
  resume { i8*, i32 } %exception
}

attributes #0 = { convergent }
attributes #1 = { noduplicate }

!llvm.dbg.cu = !{!0}
!llvm.module.flags = !{!2, !3}
!0 = distinct !DICompileUnit(language: DW_LANG_C99, file: !1, producer: "arc-test", isOptimized: false, runtimeVersion: 0, emissionKind: FullDebug)
!1 = !DIFile(filename: "arc-test.kt", directory: "/")
!2 = !{i32 2, !"Dwarf Version", i32 4}
!3 = !{i32 2, !"Debug Info Version", i32 3}
!4 = distinct !DISubprogram(name: "debugSeparator", scope: !1, file: !1, line: 1, type: !5, scopeLine: 1, spFlags: DISPFlagDefinition, unit: !0)
!5 = !DISubroutineType(types: !6)
!6 = !{null}
!7 = !DIBasicType(name: "pointer", size: 64, encoding: DW_ATE_address)
!8 = !DILocalVariable(name: "value", scope: !4, file: !1, line: 1, type: !7)
!9 = !DILocation(line: 1, column: 1, scope: !4)
)IR";

[[noreturn]] void fail(const std::string& message) {
  errs() << "ArcReturnUpdateCoalescingTest failure: " << message << '\n';
  std::exit(1);
}

std::unique_ptr<Module> parseModule(LLVMContext& context) {
  SMDiagnostic diagnostic;
  auto module = parseAssemblyString(kModule, diagnostic, context);
  if (module == nullptr) {
    diagnostic.print("ArcReturnUpdateCoalescingTest", errs());
    fail("could not parse fixture");
  }
  return module;
}

unsigned countUpdates(const Module& module, StringRef functionName) {
  const Function* function = module.getFunction(functionName);
  if (function == nullptr) fail(("missing function " + functionName).str());
  unsigned result = 0;
  for (const BasicBlock& block : *function) {
    for (const Instruction& instruction : block) {
      const auto* call = dyn_cast<CallBase>(&instruction);
      if (call != nullptr && call->getCalledFunction() != nullptr &&
          call->getCalledFunction()->getName() == "UpdateReturnRefRelaxed") {
        ++result;
      }
    }
  }
  return result;
}

void requireCount(const Module& module, StringRef functionName, unsigned expected) {
  const unsigned actual = countUpdates(module, functionName);
  if (actual != expected) {
    fail(functionName.str() + " has " + std::to_string(actual) +
         " update(s), expected " + std::to_string(expected));
  }
}

} // namespace

int main() {
  LLVMContext context;
  auto module = parseModule(context);
  const int removed = LLVMKotlinCoalesceAdjacentArcReturnUpdates(wrap(module.get()));
  if (removed != 4) fail("expected exactly four removed calls");
  if (verifyModule(*module, &errs())) fail("transformed module is invalid");

  requireCount(*module, "adjacent", 1);
  requireCount(*module, "run", 1);
  requireCount(*module, "differentSlot", 2);
  requireCount(*module, "differentValue", 2);
  requireCount(*module, "intervening", 2);
  requireCount(*module, "lifetimeSeparator", 2);
  requireCount(*module, "debugSeparator", 1);
  requireCount(*module, "callSeparator", 2);
  requireCount(*module, "storeSeparator", 2);
  requireCount(*module, "loadSeparator", 2);
  requireCount(*module, "operandBundle", 2);
  requireCount(*module, "convergentCalls", 2);
  requireCount(*module, "noduplicateCalls", 2);
  requireCount(*module, "invokeUpdates", 2);

  outs() << "ArcReturnUpdateCoalescingTest: OK\n";
  return 0;
}
