/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */

#include <ArcSelectiveInlining.h>

#include <cstdlib>
#include <memory>

#include <llvm/AsmParser/Parser.h>
#include <llvm/IR/Function.h>
#include <llvm/IR/Instructions.h>
#include <llvm/IR/LLVMContext.h>
#include <llvm/IR/Module.h>
#include <llvm/IR/Verifier.h>
#include <llvm/IR/LegacyPassManager.h>
#include <llvm/Linker/Linker.h>
#include <llvm/Support/CBindingWrapping.h>
#include <llvm/Support/SourceMgr.h>
#include <llvm/Transforms/IPO.h>

using namespace llvm;

namespace {

[[noreturn]] void fail(const char* condition) {
  errs() << "ArcSelectiveInliningTest failure: " << condition << '\n';
  std::exit(1);
}

void require(bool condition, const char* description) {
  if (!condition) fail(description);
}

#define REQUIRE(condition) require((condition), #condition)

std::unique_ptr<Module> parse(LLVMContext& context, const char* text) {
  SMDiagnostic error;
  auto module = parseAssemblyString(text, error, context);
  REQUIRE(module && "test IR must parse");
  return module;
}

size_t directCalls(Function& function, StringRef calleeName) {
  size_t count = 0;
  for (BasicBlock& block : function)
    for (Instruction& instruction : block)
      if (auto* call = dyn_cast<CallBase>(&instruction))
        if (call->getCalledFunction() && call->getCalledFunction()->getName() == calleeName) ++count;
  return count;
}

} // namespace

int main() {
  LLVMContext context;
  auto source = parse(context, R"IR(
declare i32 @externalDependency(i32)
define internal i32 @localHelper(i32 %x) {
entry:
  %seed = load i32, i32* @localAlias
  %y = add i32 %x, %seed
  ret i32 %y
}
@localSeed = internal global i32 1
@localAlias = internal alias i32, i32* @localSeed
define i32 @canonicalAppend(i32 %x) {
entry:
  %a = call i32 @localHelper(i32 %x)
  %b = call i32 @externalDependency(i32 %a)
  ret i32 %b
}
define i32 @unrelatedDefinition(i32 %x) {
entry:
  ret i32 %x
}
)IR");

  LLVMModuleRef companionRef = LLVMKotlinCreateArcSelectiveInlineCompanion(
      wrap(source.get()), "canonicalAppend", "__arc_append_clone");
  REQUIRE(companionRef);
  std::unique_ptr<Module> companion(unwrap(companionRef));
  Function* clone = companion->getFunction("__arc_append_clone");
  REQUIRE(clone && !clone->isDeclaration() && clone->hasExternalLinkage() && clone->hasHiddenVisibility());
  REQUIRE(companion->getFunction("localHelper") && !companion->getFunction("localHelper")->isDeclaration());
  REQUIRE(companion->getNamedGlobal("localSeed") && companion->getNamedGlobal("localSeed")->hasInitializer());
  REQUIRE(companion->getNamedAlias("localAlias"));
  REQUIRE(companion->getFunction("externalDependency") && companion->getFunction("externalDependency")->isDeclaration());
  REQUIRE(companion->getFunction("unrelatedDefinition") && companion->getFunction("unrelatedDefinition")->isDeclaration());

  auto destination = parse(context, R"IR(
declare i32 @canonicalAppend(i32)
define i32 @tagged(i32 %x) {
entry:
  %a = call i32 @canonicalAppend(i32 %x), !konan.arc.inline !0
  %b = call i32 @canonicalAppend(i32 %a), !konan.arc.inline !0
  ret i32 %b
}
define i32 @untagged(i32 %x) {
entry:
  %a = call i32 @canonicalAppend(i32 %x)
  ret i32 %a
}
!0 = !{}
)IR");
  REQUIRE(!Linker::linkModules(*destination, std::move(companion)));
  REQUIRE(!verifyModule(*destination, &errs()));

  LLVMKotlinArcSelectiveInlineStats stats = LLVMKotlinInlineArcTaggedCalls(
      wrap(destination.get()), "konan.arc.inline", "canonicalAppend", "__arc_append_clone",
      16, 4, 4);
  REQUIRE(stats.tagged == 2);
  REQUIRE(stats.inlined == 1);
  REQUIRE(stats.budgetSkipped == 1);
  REQUIRE(stats.rejected == 0 && stats.missingBody == 0 && stats.inlineFailures == 0);
  REQUIRE(directCalls(*destination->getFunction("tagged"), "canonicalAppend") == 1);
  REQUIRE(directCalls(*destination->getFunction("untagged"), "canonicalAppend") == 1);
  REQUIRE(!verifyModule(*destination, &errs()));

  // A tagged indirect/type-adapted call and a tagged call to any other callee
  // must not acquire semantics merely because the metadata spelling matches.
  auto rejected = parse(context, R"IR(
declare i32 @canonicalAppend(i32)
declare i32 @wrongCallee(i32)
define i32 @wrong(i32 %x) {
entry:
  %a = call i32 @wrongCallee(i32 %x), !konan.arc.inline !0
  %typed = bitcast i32 (i32)* @canonicalAppend to i64 (i64)*
  %b = call i64 %typed(i64 1), !konan.arc.inline !0
  %c = trunc i64 %b to i32
  %d = add i32 %a, %c
  ret i32 %d
}
!0 = !{}
)IR");
  auto rejectedCompanion = parse(context, R"IR(
define hidden i32 @__arc_append_clone(i32 %x) { ret i32 %x }
)IR");
  REQUIRE(!Linker::linkModules(*rejected, std::move(rejectedCompanion)));
  auto rejectedStats = LLVMKotlinInlineArcTaggedCalls(
      wrap(rejected.get()), "konan.arc.inline", "canonicalAppend", "__arc_append_clone", 16, 16, 16);
  REQUIRE(rejectedStats.tagged == 2 && rejectedStats.rejected == 2 && rejectedStats.inlined == 0);
  REQUIRE(!verifyModule(*rejected, &errs()));

  // Both call and invoke sites use the same CallBase path.
  auto invokeModule = parse(context, R"IR(
declare i32 @__gxx_personality_v0(...)
define i32 @canonicalAppend(i32 %x) { %y = add i32 %x, 1 ret i32 %y }
define i32 @invokeCaller(i32 %x) personality i32 (...)* @__gxx_personality_v0 {
entry:
  %y = invoke i32 @canonicalAppend(i32 %x) to label %done unwind label %failed, !konan.arc.inline !0
done:
  ret i32 %y
failed:
  %lp = landingpad { i8*, i32 } cleanup
  ret i32 0
}
!0 = !{}
)IR");
  auto invokeStats = LLVMKotlinInlineArcTaggedCalls(
      wrap(invokeModule.get()), "konan.arc.inline", "canonicalAppend", "__arc_append_clone", 16, 16, 16);
  REQUIRE(invokeStats.tagged == 1 && invokeStats.inlined == 1 && invokeStats.inlineFailures == 0);
  REQUIRE(!verifyModule(*invokeModule, &errs()));

  auto missing = parse(context, R"IR(
declare i32 @canonicalAppend(i32)
define i32 @caller(i32 %x) { %y = call i32 @canonicalAppend(i32 %x), !konan.arc.inline !0 ret i32 %y }
!0 = !{}
)IR");
  auto missingStats = LLVMKotlinInlineArcTaggedCalls(
      wrap(missing.get()), "konan.arc.inline", "canonicalAppend", "__arc_append_clone", 16, 16, 16);
  REQUIRE(missingStats.tagged == 1 && missingStats.missingBody == 1 && missingStats.inlined == 0);

  // Exact budgets are independently enforced.
  const char* budgetIr = R"IR(
define i32 @canonicalAppend(i32 %x) { %a = add i32 %x, 1 %b = add i32 %a, 1 ret i32 %b }
define i32 @caller(i32 %x) { %y = call i32 @canonicalAppend(i32 %x), !konan.arc.inline !0 ret i32 %y }
!0 = !{}
)IR";
  auto calleeBudget = parse(context, budgetIr);
  auto calleeBudgetStats = LLVMKotlinInlineArcTaggedCalls(
      wrap(calleeBudget.get()), "konan.arc.inline", "canonicalAppend", "__arc_append_clone", 1, 16, 16);
  REQUIRE(calleeBudgetStats.rejected == 1 && calleeBudgetStats.inlined == 0);
  auto callerBudget = parse(context, budgetIr);
  auto callerBudgetStats = LLVMKotlinInlineArcTaggedCalls(
      wrap(callerBudget.get()), "konan.arc.inline", "canonicalAppend", "__arc_append_clone", 16, 1, 100);
  REQUIRE(callerBudgetStats.budgetSkipped == 1 && callerBudgetStats.inlined == 0);
  auto moduleBudget = parse(context, budgetIr);
  auto moduleBudgetStats = LLVMKotlinInlineArcTaggedCalls(
      wrap(moduleBudget.get()), "konan.arc.inline", "canonicalAppend", "__arc_append_clone", 16, 100, 1);
  REQUIRE(moduleBudgetStats.budgetSkipped == 1 && moduleBudgetStats.inlined == 0);

  // Varargs and clone-name collisions cannot produce companions.
  auto vararg = parse(context, R"IR(define i32 @canonicalAppend(i32 %x, ...) { ret i32 %x })IR");
  REQUIRE(!LLVMKotlinCreateArcSelectiveInlineCompanion(
      wrap(vararg.get()), "canonicalAppend", "__arc_append_clone"));
  auto collision = parse(context, R"IR(
define i32 @canonicalAppend(i32 %x) { ret i32 %x }
declare i32 @__arc_append_clone(i32)
)IR");
  REQUIRE(!LLVMKotlinCreateArcSelectiveInlineCompanion(
      wrap(collision.get()), "canonicalAppend", "__arc_append_clone"));

  // The normal final pipeline's GlobalDCE removes the now-unreferenced private
  // companion root while retaining helpers still referenced by inlined code.
  legacy::PassManager passes;
  passes.add(createGlobalDCEPass());
  passes.run(*destination);
  REQUIRE(destination->getFunction("__arc_append_clone") == nullptr);
  REQUIRE(!verifyModule(*destination, &errs()));
  return 0;
}
