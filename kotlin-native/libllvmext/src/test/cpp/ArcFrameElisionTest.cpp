/*
 * Copyright 2010-2026 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

#include <ArcFrameElision.h>

#include <cstdlib>
#include <memory>
#include <string>
#include <vector>

#include <llvm/AsmParser/Parser.h>
#include <llvm/IR/Attributes.h>
#include <llvm/IR/Constants.h>
#include <llvm/IR/Function.h>
#include <llvm/IR/GlobalVariable.h>
#include <llvm/IR/InlineAsm.h>
#include <llvm/IR/Instructions.h>
#include <llvm/IR/LLVMContext.h>
#include <llvm/IR/Module.h>
#include <llvm/IR/Verifier.h>
#include <llvm/Support/CBindingWrapping.h>
#include <llvm/Support/SourceMgr.h>
#include <llvm/Support/raw_ostream.h>

using namespace llvm;

namespace {

constexpr const char* kPreparedMarker = "konan.arc.empty-frame-elision.prepared";
constexpr const char* kInsertedUsedMarker = "konan.arc.empty-frame-elision.inserted-used";
constexpr const char* kLeaveShapeGuardFunctionMarker = "konan.arc.empty-frame-elision.leave-shape-guard";
constexpr const char* kLeaveShapeGuardCallMarker = "konan.arc.empty-frame-elision.leave-shape-guard-call";

constexpr const char* kPreservedUsedModule = R"IR(
@llvm.used = appending global [3 x i8*] [
  i8* bitcast (void ()* @anchor_before to i8*),
  i8* bitcast (void (i8**, i32, i32)* @EnterFrame to i8*),
  i8* bitcast (void ()* @anchor_after to i8*)
], section "llvm.metadata"

define void @anchor_before() { ret void }
define void @anchor_after() { ret void }
define void @guard_target() { ret void }

define void @EnterFrame(i8** %frame, i32 %parameters, i32 %count) {
  ret void
}

define void @LeaveFrame(i8** %frame, i32 %parameters, i32 %count) {
  call void @guard_target()
  ret void
}

define void @SetCurrentFrame(i8** %frame) {
  ret void
}
)IR";

constexpr const char* kMismatchedFrameModule = R"IR(
define void @EnterFrame(i8** %frame, i32 %parameters, i32 %count) {
  ret void
}

define void @LeaveFrame(i8** %frame, i32 %parameters, i32 %count) {
  ret void
}

define void @SetCurrentFrame(i8** %frame) {
  ret void
}

define void @"kfun:mismatched#internal"() {
entry:
  %frame = alloca [5 x i8*]
  %raw = bitcast [5 x i8*]* %frame to i8**
  call void @EnterFrame(i8** %raw, i32 0, i32 5) [ "arc.test.retained"() ]
  call void @LeaveFrame(i8** %raw, i32 1, i32 5)
  ret void
}
)IR";

[[noreturn]] void fail(const Twine& message) {
    errs() << "ArcFrameElisionTest failure: " << message << '\n';
    std::exit(1);
}

void require(bool condition, const Twine& message) {
    if (!condition) fail(message);
}

std::unique_ptr<Module> parseModule(LLVMContext& context, StringRef ir) {
    SMDiagnostic diagnostic;
    std::unique_ptr<Module> module = parseAssemblyString(ir, diagnostic, context);
    if (module == nullptr) {
        diagnostic.print("ArcFrameElisionTest", errs());
        fail("could not parse test module");
    }
    require(!verifyModule(*module, &errs()), "input module did not verify");
    return module;
}

std::vector<const Value*> usedTargets(const Module& module) {
    const GlobalVariable* used = module.getGlobalVariable("llvm.used", true);
    require(used != nullptr && used->hasInitializer(), "llvm.used is missing");
    const auto* initializer = dyn_cast<ConstantArray>(used->getInitializer());
    require(initializer != nullptr, "llvm.used initializer is not an array");
    std::vector<const Value*> result;
    for (const Use& operand : initializer->operands()) {
        result.push_back(operand.get()->stripPointerCasts());
    }
    return result;
}

unsigned countGuardCalls(const Function& function) {
    unsigned result = 0;
    for (const BasicBlock& block : function) {
        for (const Instruction& instruction : block) {
            const auto* call = dyn_cast<CallInst>(&instruction);
            if (call != nullptr && call->hasFnAttr(kLeaveShapeGuardCallMarker)) ++result;
        }
    }
    return result;
}

CallInst* callTo(Function& caller, const Function& callee) {
    for (BasicBlock& block : caller) {
        for (Instruction& instruction : block) {
            auto* call = dyn_cast<CallInst>(&instruction);
            if (call != nullptr && call->getCalledOperand()->stripPointerCasts() == &callee) return call;
        }
    }
    return nullptr;
}

void requireWrappersRestored(Module& module, bool allowSpoofedGuardCall = false) {
    for (const char* name : {"EnterFrame", "LeaveFrame", "SetCurrentFrame"}) {
        Function* function = module.getFunction(name);
        require(function != nullptr, Twine("missing wrapper ") + name);
        require(!function->hasFnAttribute(Attribute::NoInline), Twine(name) + " retained noinline");
        require(!function->hasFnAttribute(kPreparedMarker), Twine(name) + " retained prepared marker");
        require(!function->hasFnAttribute(kInsertedUsedMarker), Twine(name) + " retained used marker");
    }
    Function* leave = module.getFunction("LeaveFrame");
    require(!leave->hasFnAttribute(kLeaveShapeGuardFunctionMarker), "LeaveFrame retained shape-guard marker");
    if (!allowSpoofedGuardCall) {
        require(countGuardCalls(*leave) == 0, "LeaveFrame retained shape-guard call");
    }
}

void testPreexistingUsedAndRestoreOnly() {
    LLVMContext context;
    std::unique_ptr<Module> module = parseModule(context, kPreservedUsedModule);
    const std::vector<const Value*> original = usedTargets(*module);
    require(original.size() == 3, "unexpected original llvm.used size");

    require(LLVMKotlinPrepareArcFrameElision(wrap(module.get())) == 1, "prepare failed");
    require(LLVMKotlinSealArcFrameElisionForLTO(wrap(module.get())) == 1, "seal failed");

    const std::vector<const Value*> sealed = usedTargets(*module);
    require(sealed.size() == 5, "seal did not append exactly the two missing wrappers");
    require(sealed[0] == original[0] && sealed[1] == original[1] && sealed[2] == original[2],
            "seal reordered preexisting llvm.used entries");
    require(!module->getFunction("EnterFrame")->hasFnAttribute(kInsertedUsedMarker),
            "seal marked a preexisting llvm.used wrapper as inserted");
    require(module->getFunction("LeaveFrame")->hasFnAttribute(kInsertedUsedMarker), "seal did not mark an appended wrapper");
    require(module->getFunction("SetCurrentFrame")->hasFnAttribute(kInsertedUsedMarker), "seal did not mark an appended wrapper");
    require(countGuardCalls(*module->getFunction("LeaveFrame")) == 1, "seal did not install exactly one shape guard");

    LLVMKotlinRestoreArcFrameElision(wrap(module.get()));
    const std::vector<const Value*> restored = usedTargets(*module);
    require(restored == original, "restore did not reproduce llvm.used exactly");
    requireWrappersRestored(*module);
    require(callTo(*module->getFunction("LeaveFrame"), *module->getFunction("guard_target")) != nullptr,
            "restore removed a preexisting LeaveFrame call");
    require(!verifyModule(*module, &errs()), "restored module did not verify");
}

void testSpoofedGuardFailsClosed() {
    LLVMContext context;
    std::unique_ptr<Module> module = parseModule(context, kPreservedUsedModule);
    const std::vector<const Value*> original = usedTargets(*module);
    CallInst* spoof = callTo(*module->getFunction("LeaveFrame"), *module->getFunction("guard_target"));
    require(spoof != nullptr, "missing spoof call site");
    spoof->addAttribute(AttributeList::FunctionIndex, Attribute::get(context, kLeaveShapeGuardCallMarker));

    require(LLVMKotlinPrepareArcFrameElision(wrap(module.get())) == 1, "spoof prepare failed");
    require(LLVMKotlinSealArcFrameElisionForLTO(wrap(module.get())) == 0, "seal accepted a spoofed shape-guard call");
    LLVMKotlinRestoreArcFrameElision(wrap(module.get()));

    require(usedTargets(*module) == original, "failed seal changed llvm.used");
    requireWrappersRestored(*module, true);
    require(spoof->hasFnAttr(kLeaveShapeGuardCallMarker), "restore changed the spoofed call");
    require(!verifyModule(*module, &errs()), "spoof-restored module did not verify");
}

void testMismatchAndRetainedNonInlineableCall() {
    LLVMContext context;
    std::unique_ptr<Module> module = parseModule(context, kMismatchedFrameModule);
    Function* body = module->getFunction("kfun:mismatched#internal");
    require(body != nullptr, "missing mismatched Kotlin body");

    require(LLVMKotlinPrepareArcFrameElision(wrap(module.get())) == 1, "mismatch prepare failed");
    require(LLVMKotlinRemoveEmptyArcFrames(wrap(module.get())) == 0, "mismatched EnterFrame/LeaveFrame constants were elided");
    require(LLVMKotlinCountDirectArcFrameWrapperCalls(wrap(module.get())) == 1, "non-inlineable retained wrapper call was not reported");
    require(callTo(*body, *module->getFunction("EnterFrame")) != nullptr, "non-inlineable retained wrapper call was removed");
    bool hasFrameStorage = false;
    for (Instruction& instruction : body->getEntryBlock()) {
        if (const auto* alloca = dyn_cast<AllocaInst>(&instruction)) {
            hasFrameStorage = hasFrameStorage || alloca->getName() == "frame";
        }
    }
    require(hasFrameStorage, "mismatched frame storage was removed");
    requireWrappersRestored(*module);
    require(!verifyModule(*module, &errs()), "mismatch-restored module did not verify");
}

} // namespace

int main() {
    testPreexistingUsedAndRestoreOnly();
    testSpoofedGuardFailsClosed();
    testMismatchAndRetainedNonInlineableCall();
    outs() << "ArcFrameElisionTest: OK\n";
    return 0;
}
