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
#include <llvm/IR/IntrinsicInst.h>
#include <llvm/IR/Intrinsics.h>
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

constexpr const char* kUniqueFrameWithoutLifetimeModule = R"IR(
target datalayout = "e-p:64:64"

declare void @llvm.memset.p0i8.i64(i8* nocapture writeonly, i8, i64, i1 immarg)
declare void @enter_effect()
declare void @leave_effect()

define void @EnterFrame(i8** %frame, i32 %parameters, i32 %count) {
  call void @enter_effect()
  ret void
}

define void @LeaveFrame(i8** %frame, i32 %parameters, i32 %count) {
  call void @leave_effect()
  ret void
}

define void @SetCurrentFrame(i8** %frame) { ret void }

define void @"kfun:uniqueWithoutLifetime#internal"(i32* %scalar, i1 %selector) {
entry:
  %frame = alloca [5 x i8*]
  %raw = bitcast [5 x i8*]* %frame to i8**
  %bytes = bitcast [5 x i8*]* %frame to i8*
  call void @llvm.memset.p0i8.i64(i8* %bytes, i8 0, i64 40, i1 false)
  call void @EnterFrame(i8** %raw, i32 0, i32 5)
  %loaded = load i32, i32* %scalar
  br i1 %selector, label %left, label %right
left:
  %left.value = add i32 %loaded, 2
  br label %join
right:
  %right.value = sub i32 %loaded, 2
  br label %join
join:
  %value = phi i32 [ %left.value, %left ], [ %right.value, %right ]
  call void @LeaveFrame(i8** %raw, i32 0, i32 5)
  ret void
}

define void @"kfun:uniqueWithoutInitialization#internal"() {
entry:
  %frame = alloca [5 x i8*]
  %raw = bitcast [5 x i8*]* %frame to i8**
  call void @EnterFrame(i8** %raw, i32 0, i32 5)
  %value = add i32 20, 22
  call void @LeaveFrame(i8** %raw, i32 0, i32 5)
  ret void
}
)IR";

constexpr const char* kBundledFrameModule = R"IR(
target datalayout = "e-p:64:64"

declare void @llvm.lifetime.start.p0i8(i64 immarg, i8* nocapture)
declare void @llvm.lifetime.end.p0i8(i64 immarg, i8* nocapture)
declare void @llvm.memset.p0i8.i64(i8* nocapture writeonly, i8, i64, i1 immarg)

define void @EnterFrame(i8** %frame, i32 %parameters, i32 %count) {
  ret void
}

define void @LeaveFrame(i8** %frame, i32 %parameters, i32 %count) {
  ret void
}

define void @SetCurrentFrame(i8** %frame) {
  ret void
}

define void @"kfun:bundled#internal"() {
entry:
  %frame = alloca [5 x i8*]
  %raw = bitcast [5 x i8*]* %frame to i8**
  %bytes = bitcast [5 x i8*]* %frame to i8*
  call void @llvm.lifetime.start.p0i8(i64 40, i8* %bytes)
  call void @llvm.memset.p0i8.i64(i8* %bytes, i8 0, i64 40, i1 false)
  call void @EnterFrame(i8** %raw, i32 0, i32 5) [ "arc.test.retained"() ]
  call void @LeaveFrame(i8** %raw, i32 0, i32 5)
  call void @llvm.lifetime.end.p0i8(i64 40, i8* %bytes)
  ret void
}
)IR";

constexpr const char* kSharedFrameModule = R"IR(
target datalayout = "e-p:64:64"

declare void @llvm.lifetime.start.p0i8(i64 immarg, i8* nocapture)
declare void @llvm.lifetime.end.p0i8(i64 immarg, i8* nocapture)
declare void @llvm.memset.p0i8.i64(i8* nocapture writeonly, i8, i64, i1 immarg)
declare void @opaque(i8** nocapture)
declare void @enter_effect()
declare void @leave_effect()

define void @EnterFrame(i8** %frame, i32 %parameters, i32 %count) {
  call void @enter_effect()
  ret void
}

define void @LeaveFrame(i8** %frame, i32 %parameters, i32 %count) {
  call void @leave_effect()
  ret void
}

define void @SetCurrentFrame(i8** %frame) {
  ret void
}

define void @"kfun:shared#internal"(i2 %selector) {
entry:
  %frame = alloca [5 x i8*]
  %raw = bitcast [5 x i8*]* %frame to i8**
  %bytes = bitcast [5 x i8*]* %frame to i8*
  switch i2 %selector, label %retained_after [
    i2 0, label %retained_before
    i2 1, label %empty_interval
  ]

retained_before:
  call void @llvm.lifetime.start.p0i8(i64 40, i8* %bytes)
  call void @llvm.memset.p0i8.i64(i8* %bytes, i8 0, i64 40, i1 false)
  call void @EnterFrame(i8** %raw, i32 0, i32 5)
  call void @opaque(i8** nocapture %raw)
  call void @LeaveFrame(i8** %raw, i32 0, i32 5)
  call void @llvm.lifetime.end.p0i8(i64 40, i8* %bytes)
  br label %exit

empty_interval:
  call void @llvm.lifetime.start.p0i8(i64 40, i8* %bytes)
  call void @llvm.memset.p0i8.i64(i8* %bytes, i8 0, i64 40, i1 false)
  call void @EnterFrame(i8** %raw, i32 0, i32 5)
  %value = add i32 40, 2
  call void @LeaveFrame(i8** %raw, i32 0, i32 5)
  call void @llvm.lifetime.end.p0i8(i64 40, i8* %bytes)
  br label %exit

retained_after:
  call void @llvm.lifetime.start.p0i8(i64 40, i8* %bytes)
  call void @llvm.memset.p0i8.i64(i8* %bytes, i8 0, i64 40, i1 false)
  call void @EnterFrame(i8** %raw, i32 0, i32 5)
  call void @opaque(i8** nocapture %raw)
  call void @LeaveFrame(i8** %raw, i32 0, i32 5)
  call void @llvm.lifetime.end.p0i8(i64 40, i8* %bytes)
  br label %exit

exit:
  ret void
}
)IR";

constexpr const char* kMismatchedFrameModule = R"IR(
target datalayout = "e-p:64:64"

declare void @llvm.lifetime.start.p0i8(i64 immarg, i8* nocapture)
declare void @llvm.lifetime.end.p0i8(i64 immarg, i8* nocapture)
declare void @llvm.memset.p0i8.i64(i8* nocapture writeonly, i8, i64, i1 immarg)

define void @EnterFrame(i8** %frame, i32 %parameters, i32 %count) { ret void }
define void @LeaveFrame(i8** %frame, i32 %parameters, i32 %count) { ret void }
define void @SetCurrentFrame(i8** %frame) { ret void }

define void @"kfun:mismatched#internal"() {
entry:
  %frame = alloca [5 x i8*]
  %raw = bitcast [5 x i8*]* %frame to i8**
  %bytes = bitcast [5 x i8*]* %frame to i8*
  call void @llvm.lifetime.start.p0i8(i64 40, i8* %bytes)
  call void @llvm.memset.p0i8.i64(i8* %bytes, i8 0, i64 40, i1 false)
  call void @EnterFrame(i8** %raw, i32 0, i32 5)
  call void @LeaveFrame(i8** %raw, i32 1, i32 5)
  call void @llvm.lifetime.end.p0i8(i64 40, i8* %bytes)
  ret void
}
)IR";

constexpr const char* kRejectedScopedFramesModule = R"IR(
target datalayout = "e-p:64:64"

declare void @llvm.lifetime.start.p0i8(i64 immarg, i8* nocapture)
declare void @llvm.lifetime.end.p0i8(i64 immarg, i8* nocapture)
declare void @llvm.memset.p0i8.i64(i8* nocapture writeonly, i8, i64, i1 immarg)

define void @EnterFrame(i8** %frame, i32 %parameters, i32 %count) { ret void }
define void @LeaveFrame(i8** %frame, i32 %parameters, i32 %count) { ret void }
define void @SetCurrentFrame(i8** %frame) { ret void }

define void @"kfun:dynamicGep#internal"(i64 %index) {
entry:
  %frame = alloca [5 x i8*]
  %raw = getelementptr inbounds [5 x i8*], [5 x i8*]* %frame, i64 0, i64 %index
  %bytes = bitcast [5 x i8*]* %frame to i8*
  call void @llvm.lifetime.start.p0i8(i64 40, i8* %bytes)
  call void @llvm.memset.p0i8.i64(i8* %bytes, i8 0, i64 40, i1 false)
  call void @EnterFrame(i8** %raw, i32 0, i32 5)
  call void @LeaveFrame(i8** %raw, i32 0, i32 5)
  call void @llvm.lifetime.end.p0i8(i64 40, i8* %bytes)
  ret void
}

define void @"kfun:nonInboundsGep#internal"() {
entry:
  %frame = alloca [5 x i8*]
  %raw = getelementptr [5 x i8*], [5 x i8*]* %frame, i64 0, i64 0
  %bytes = bitcast [5 x i8*]* %frame to i8*
  call void @llvm.lifetime.start.p0i8(i64 40, i8* %bytes)
  call void @llvm.memset.p0i8.i64(i8* %bytes, i8 0, i64 40, i1 false)
  call void @EnterFrame(i8** %raw, i32 0, i32 5)
  call void @LeaveFrame(i8** %raw, i32 0, i32 5)
  call void @llvm.lifetime.end.p0i8(i64 40, i8* %bytes)
  ret void
}

define void @"kfun:partialLifetime#internal"() {
entry:
  %frame = alloca [5 x i8*]
  %raw = bitcast [5 x i8*]* %frame to i8**
  %bytes = bitcast [5 x i8*]* %frame to i8*
  call void @llvm.lifetime.start.p0i8(i64 32, i8* %bytes)
  call void @llvm.memset.p0i8.i64(i8* %bytes, i8 0, i64 40, i1 false)
  call void @EnterFrame(i8** %raw, i32 0, i32 5)
  call void @LeaveFrame(i8** %raw, i32 0, i32 5)
  call void @llvm.lifetime.end.p0i8(i64 32, i8* %bytes)
  ret void
}

define void @"kfun:duplicateMemset#internal"() {
entry:
  %frame = alloca [5 x i8*]
  %raw = bitcast [5 x i8*]* %frame to i8**
  %bytes = bitcast [5 x i8*]* %frame to i8*
  call void @llvm.lifetime.start.p0i8(i64 40, i8* %bytes)
  call void @llvm.memset.p0i8.i64(i8* %bytes, i8 0, i64 40, i1 false)
  call void @llvm.memset.p0i8.i64(i8* %bytes, i8 0, i64 40, i1 false)
  call void @EnterFrame(i8** %raw, i32 0, i32 5)
  call void @LeaveFrame(i8** %raw, i32 0, i32 5)
  call void @llvm.lifetime.end.p0i8(i64 40, i8* %bytes)
  ret void
}

define void @"kfun:nested#internal"() {
entry:
  %frame = alloca [5 x i8*]
  %raw = bitcast [5 x i8*]* %frame to i8**
  %bytes = bitcast [5 x i8*]* %frame to i8*
  call void @llvm.lifetime.start.p0i8(i64 40, i8* %bytes)
  call void @llvm.memset.p0i8.i64(i8* %bytes, i8 0, i64 40, i1 false)
  call void @EnterFrame(i8** %raw, i32 0, i32 5)
  call void @llvm.lifetime.start.p0i8(i64 40, i8* %bytes)
  call void @llvm.memset.p0i8.i64(i8* %bytes, i8 0, i64 40, i1 false)
  call void @EnterFrame(i8** %raw, i32 0, i32 5)
  call void @LeaveFrame(i8** %raw, i32 0, i32 5)
  call void @llvm.lifetime.end.p0i8(i64 40, i8* %bytes)
  call void @LeaveFrame(i8** %raw, i32 0, i32 5)
  call void @llvm.lifetime.end.p0i8(i64 40, i8* %bytes)
  ret void
}

define void @"kfun:unreachableBypass#internal"(i1 %takeExit) {
entry:
  %frame = alloca [5 x i8*]
  %raw = bitcast [5 x i8*]* %frame to i8**
  %bytes = bitcast [5 x i8*]* %frame to i8*
  call void @llvm.lifetime.start.p0i8(i64 40, i8* %bytes)
  call void @llvm.memset.p0i8.i64(i8* %bytes, i8 0, i64 40, i1 false)
  call void @EnterFrame(i8** %raw, i32 0, i32 5)
  br i1 %takeExit, label %exit, label %dead
exit:
  call void @LeaveFrame(i8** %raw, i32 0, i32 5)
  call void @llvm.lifetime.end.p0i8(i64 40, i8* %bytes)
  ret void
dead:
  unreachable
}

define void @"kfun:markerlessShared#internal"() {
entry:
  %frame = alloca [5 x i8*]
  %raw = bitcast [5 x i8*]* %frame to i8**
  call void @EnterFrame(i8** %raw, i32 0, i32 5)
  call void @LeaveFrame(i8** %raw, i32 0, i32 5)
  call void @EnterFrame(i8** %raw, i32 0, i32 5)
  call void @LeaveFrame(i8** %raw, i32 0, i32 5)
  ret void
}

define void @"kfun:markerlessDerivedLeaf#internal"() {
entry:
  %frame = alloca [5 x i8*]
  %raw = bitcast [5 x i8*]* %frame to i8**
  %slot = getelementptr inbounds [5 x i8*], [5 x i8*]* %frame, i64 0, i64 4
  store i8* null, i8** %slot
  call void @EnterFrame(i8** %raw, i32 0, i32 5)
  call void @LeaveFrame(i8** %raw, i32 0, i32 5)
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

unsigned countCallsTo(const Function& caller, const Function& callee) {
    unsigned result = 0;
    for (const BasicBlock& block : caller) {
        for (const Instruction& instruction : block) {
            const auto* call = dyn_cast<CallBase>(&instruction);
            if (call != nullptr && call->getCalledOperand()->stripPointerCasts() == &callee) ++result;
        }
    }
    return result;
}

unsigned countIntrinsics(const Function& function, Intrinsic::ID id) {
    unsigned result = 0;
    for (const BasicBlock& block : function) {
        for (const Instruction& instruction : block) {
            const auto* intrinsic = dyn_cast<IntrinsicInst>(&instruction);
            if (intrinsic != nullptr && intrinsic->getIntrinsicID() == id) ++result;
        }
    }
    return result;
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

void testUniqueFrameWithoutLifetimeErasesWholeStorage() {
    LLVMContext context;
    std::unique_ptr<Module> module = parseModule(context, kUniqueFrameWithoutLifetimeModule);
    Function* body = module->getFunction("kfun:uniqueWithoutLifetime#internal");
    Function* uninitializedBody = module->getFunction("kfun:uniqueWithoutInitialization#internal");
    require(body != nullptr, "missing unique no-lifetime Kotlin body");
    require(uninitializedBody != nullptr, "missing unique no-initialization Kotlin body");

    require(LLVMKotlinPrepareArcFrameElision(wrap(module.get())) == 1,
            "unique no-lifetime prepare failed");
    require(LLVMKotlinRemoveEmptyArcFrames(wrap(module.get())) == 2,
            "unique frames with optional setup were not both elided");
    require(countCallsTo(*body, *module->getFunction("enter_effect")) == 0,
            "unique no-lifetime EnterFrame effect remained");
    require(countCallsTo(*body, *module->getFunction("leave_effect")) == 0,
            "unique no-lifetime LeaveFrame effect remained");
    require(countCallsTo(*uninitializedBody, *module->getFunction("enter_effect")) == 0,
            "unique no-initialization EnterFrame effect remained");
    require(countCallsTo(*uninitializedBody, *module->getFunction("leave_effect")) == 0,
            "unique no-initialization LeaveFrame effect remained");
    require(countIntrinsics(*body, Intrinsic::memset) == 0,
            "unique no-lifetime memset remained");
    for (Instruction& instruction : body->getEntryBlock()) {
        require(!isa<AllocaInst>(instruction), "unique no-lifetime storage remained");
    }
    for (Instruction& instruction : uninitializedBody->getEntryBlock()) {
        require(!isa<AllocaInst>(instruction), "unique no-initialization storage remained");
    }
    requireWrappersRestored(*module);
    require(!verifyModule(*module, &errs()), "unique no-lifetime result did not verify");
}

void testExactBundledFrameIsRetained() {
    LLVMContext context;
    std::unique_ptr<Module> module = parseModule(context, kBundledFrameModule);
    Function* body = module->getFunction("kfun:bundled#internal");
    require(body != nullptr, "missing bundled Kotlin body");

    require(LLVMKotlinPrepareArcFrameElision(wrap(module.get())) == 1, "bundle prepare failed");
    require(LLVMKotlinRemoveEmptyArcFrames(wrap(module.get())) == 0, "exact bundled frame was elided");
    require(LLVMKotlinCountDirectArcFrameWrapperCalls(wrap(module.get())) == 1, "non-inlineable retained wrapper call was not reported");
    require(callTo(*body, *module->getFunction("EnterFrame")) != nullptr, "non-inlineable retained wrapper call was removed");
    bool hasFrameStorage = false;
    for (Instruction& instruction : body->getEntryBlock()) {
        if (const auto* alloca = dyn_cast<AllocaInst>(&instruction)) {
            hasFrameStorage = hasFrameStorage || alloca->getName() == "frame";
        }
    }
    require(hasFrameStorage, "bundled frame storage was removed");
    requireWrappersRestored(*module);
    require(!verifyModule(*module, &errs()), "bundle-restored module did not verify");
}

void testSharedAllocaElidesOnlyEmptyInterval() {
    LLVMContext context;
    std::unique_ptr<Module> module = parseModule(context, kSharedFrameModule);
    Function* body = module->getFunction("kfun:shared#internal");
    require(body != nullptr, "missing shared-allocation Kotlin body");

    require(LLVMKotlinPrepareArcFrameElision(wrap(module.get())) == 1, "shared prepare failed");
    require(LLVMKotlinRemoveEmptyArcFrames(wrap(module.get())) == 1,
            "shared-allocation empty interval was not uniquely elided");
    require(countCallsTo(*body, *module->getFunction("enter_effect")) == 2,
            "empty or retained EnterFrame effect count is wrong");
    require(countCallsTo(*body, *module->getFunction("leave_effect")) == 2,
            "empty or retained LeaveFrame effect count is wrong");
    require(countCallsTo(*body, *module->getFunction("opaque")) == 2,
            "retained nonempty interval call was changed");
    require(countIntrinsics(*body, Intrinsic::memset) == 2,
            "scoped cleanup did not remove exactly the empty interval memset");
    require(countIntrinsics(*body, Intrinsic::lifetime_start) == 3,
            "shared lifetime.start markers were changed");
    require(countIntrinsics(*body, Intrinsic::lifetime_end) == 3,
            "shared lifetime.end markers were changed");
    bool hasSharedStorage = false;
    for (Instruction& instruction : body->getEntryBlock()) {
        hasSharedStorage = hasSharedStorage || isa<AllocaInst>(instruction);
    }
    require(hasSharedStorage, "shared alloca was removed");
    requireWrappersRestored(*module);
    require(!verifyModule(*module, &errs()), "shared-allocation result did not verify");
}

void testMismatchedShapeIsRetained() {
    LLVMContext context;
    std::unique_ptr<Module> module = parseModule(context, kMismatchedFrameModule);
    require(LLVMKotlinPrepareArcFrameElision(wrap(module.get())) == 1, "mismatch prepare failed");
    require(LLVMKotlinRemoveEmptyArcFrames(wrap(module.get())) == 0,
            "mismatched EnterFrame/LeaveFrame constants were elided");
    requireWrappersRestored(*module);
    require(!verifyModule(*module, &errs()), "mismatch-restored module did not verify");
}

void testAdversarialScopedFramesFailClosed() {
    LLVMContext context;
    std::unique_ptr<Module> module = parseModule(context, kRejectedScopedFramesModule);
    require(LLVMKotlinPrepareArcFrameElision(wrap(module.get())) == 1, "adversarial prepare failed");
    require(LLVMKotlinRemoveEmptyArcFrames(wrap(module.get())) == 0,
            "an ambiguous or malformed scoped frame was elided");
    requireWrappersRestored(*module);
    require(!verifyModule(*module, &errs()), "adversarial result did not verify");
}

} // namespace

int main() {
    testPreexistingUsedAndRestoreOnly();
    testSpoofedGuardFailsClosed();
    testUniqueFrameWithoutLifetimeErasesWholeStorage();
    testExactBundledFrameIsRetained();
    testSharedAllocaElidesOnlyEmptyInterval();
    testMismatchedShapeIsRetained();
    testAdversarialScopedFramesFailClosed();
    outs() << "ArcFrameElisionTest: OK\n";
    return 0;
}
