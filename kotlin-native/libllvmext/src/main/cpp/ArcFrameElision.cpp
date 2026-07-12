/*
 * Copyright 2010-2026 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

#include <ArcFrameElision.h>

#include <cstdint>
#include <limits>

#include <llvm/ADT/DenseMap.h>
#include <llvm/ADT/DenseSet.h>
#include <llvm/ADT/SmallVector.h>
#include <llvm/Analysis/ValueTracking.h>
#include <llvm/IR/Attributes.h>
#include <llvm/IR/CFG.h>
#include <llvm/IR/Constants.h>
#include <llvm/IR/DebugInfoMetadata.h>
#include <llvm/IR/Dominators.h>
#include <llvm/IR/Function.h>
#include <llvm/IR/GlobalValue.h>
#include <llvm/IR/IRBuilder.h>
#include <llvm/IR/InlineAsm.h>
#include <llvm/IR/InstrTypes.h>
#include <llvm/IR/Instructions.h>
#include <llvm/IR/IntrinsicInst.h>
#include <llvm/IR/Intrinsics.h>
#include <llvm/IR/Module.h>
#include <llvm/Support/CBindingWrapping.h>
#include <llvm/Support/ErrorHandling.h>
#include <llvm/Transforms/Utils/Cloning.h>

using namespace llvm;

namespace {

constexpr const char* kEnterFrameName = "EnterFrame";
constexpr const char* kLeaveFrameName = "LeaveFrame";
constexpr const char* kSetCurrentFrameName = "SetCurrentFrame";
constexpr const char* kPreparedMarker = "konan.arc.empty-frame-elision.prepared";
constexpr const char* kInsertedUsedMarker = "konan.arc.empty-frame-elision.inserted-used";
constexpr const char* kLeaveShapeGuardFunctionMarker =
    "konan.arc.empty-frame-elision.leave-shape-guard";
constexpr const char* kLeaveShapeGuardCallMarker =
    "konan.arc.empty-frame-elision.leave-shape-guard-call";

struct FrameCandidate {
  AllocaInst* alloca = nullptr;
  CallInst* enter = nullptr;
  CallInst* leave = nullptr;
  uint64_t parameters = 0;
  uint64_t count = 0;
};

enum class RegionState {
  Visiting,
  Valid,
  Invalid,
};

Function* directCallee(const CallBase& call) {
  Value* called = call.getCalledOperand();
  return dyn_cast<Function>(called == nullptr ? nullptr : called->stripPointerCasts());
}

bool isDirectCallTo(const CallBase& call, const Function* function) {
  return function != nullptr && directCallee(call) == function;
}

bool readFrameShape(const CallBase& call, uint64_t& parameters, uint64_t& count) {
  if (call.arg_size() != 3) return false;
  const auto* parametersConstant = dyn_cast<ConstantInt>(call.getArgOperand(1));
  const auto* countConstant = dyn_cast<ConstantInt>(call.getArgOperand(2));
  if (parametersConstant == nullptr || countConstant == nullptr ||
      parametersConstant->getBitWidth() != 32 || countConstant->getBitWidth() != 32 ||
      parametersConstant->isNegative() || countConstant->isNegative()) {
    return false;
  }
  parameters = parametersConstant->getZExtValue();
  count = countConstant->getZExtValue();
  // FrameOverlay occupies three pointer slots on every supported Kotlin/Native ABI:
  // arena, previous, and the packed pair of 32-bit parameters/count fields.
  return parameters <= UINT32_MAX - 3 && count <= UINT32_MAX &&
         count >= parameters + 3;
}

bool hasExactFrameStorage(const AllocaInst& root, uint64_t count) {
  const auto* allocationCount = dyn_cast<ConstantInt>(root.getArraySize());
  if (allocationCount == nullptr || allocationCount->isNegative() ||
      allocationCount->getValue().getActiveBits() > 64) {
    return false;
  }
  const uint64_t allocated = allocationCount->getZExtValue();
  const Type* element = root.getAllocatedType();
  if (element->isPointerTy()) {
    // Raw Codegen form: alloca ObjHeader*, count.
    return allocated == count;
  }
  const auto* arrayType = dyn_cast<ArrayType>(element);
  // Canonical post-optimization form: alloca [count x ObjHeader*].
  return allocated == 1 && arrayType != nullptr &&
         arrayType->getNumElements() == count &&
         arrayType->getElementType()->isPointerTy();
}

bool frameStorageBytes(
    const AllocaInst& root,
    const DataLayout& dataLayout,
    uint64_t& result) {
  const auto* allocationCount = dyn_cast<ConstantInt>(root.getArraySize());
  if (allocationCount == nullptr || allocationCount->isNegative() ||
      allocationCount->getValue().getActiveBits() > 64) {
    return false;
  }
  const uint64_t allocated = allocationCount->getZExtValue();
  const uint64_t elementBytes = dataLayout.getTypeAllocSize(root.getAllocatedType());
  if (elementBytes != 0 &&
      allocated > std::numeric_limits<uint64_t>::max() / elementBytes) {
    return false;
  }
  result = allocated * elementBytes;
  return true;
}

bool isZeroMemsetOfRoot(const MemSetInst& memset, const AllocaInst& root, const DataLayout& dataLayout) {
  const auto* value = dyn_cast<ConstantInt>(memset.getValue());
  const auto* length = dyn_cast<ConstantInt>(memset.getLength());
  int64_t offset = 0;
  const Value* base = GetPointerBaseWithConstantOffset(memset.getDest(), offset, dataLayout);
  uint64_t storageBytes = 0;
  return value != nullptr && value->isZero() && !memset.isVolatile() &&
         length != nullptr && base == &root && offset == 0 &&
         frameStorageBytes(root, dataLayout, storageBytes) &&
         length->getValue().getActiveBits() <= 64 &&
         length->getZExtValue() == storageBytes;
}

bool auditFrameUses(
    Value& value,
    AllocaInst& root,
    const DataLayout& dataLayout,
    const Function* enterFunction,
    const Function* leaveFunction,
    const Function* setCurrentFrameFunction,
    DenseSet<Value*>& visited,
    SmallVectorImpl<CallInst*>& enters,
    SmallVectorImpl<CallInst*>& leaves) {
  if (!visited.insert(&value).second) return true;

  for (User* user : value.users()) {
    if (auto* cast = dyn_cast<BitCastInst>(user)) {
      if (GetUnderlyingObject(cast, dataLayout) != &root ||
          !auditFrameUses(*cast, root, dataLayout, enterFunction, leaveFunction,
                          setCurrentFrameFunction, visited, enters, leaves)) {
        return false;
      }
      continue;
    }
    if (auto* gep = dyn_cast<GetElementPtrInst>(user)) {
      if (GetUnderlyingObject(gep, dataLayout) != &root ||
          !auditFrameUses(*gep, root, dataLayout, enterFunction, leaveFunction,
                          setCurrentFrameFunction, visited, enters, leaves)) {
        return false;
      }
      continue;
    }
    // Debug builds are excluded by the driver. Reject any unexpected debug
    // storage use here so cleanup never erases an unverified leaf.
    if (isa<DbgInfoIntrinsic>(user)) return false;
    if (auto* intrinsic = dyn_cast<IntrinsicInst>(user)) {
      const Intrinsic::ID id = intrinsic->getIntrinsicID();
      if (id == Intrinsic::lifetime_start || id == Intrinsic::lifetime_end) continue;
    }
    if (auto* memset = dyn_cast<MemSetInst>(user)) {
      if (!isZeroMemsetOfRoot(*memset, root, dataLayout)) return false;
      continue;
    }
    auto* call = dyn_cast<CallInst>(user);
    if (call == nullptr || call->arg_size() == 0 ||
        GetUnderlyingObject(call->getArgOperand(0), dataLayout) != &root) {
      return false;
    }
    if (isDirectCallTo(*call, enterFunction)) {
      enters.push_back(call);
      continue;
    }
    if (isDirectCallTo(*call, leaveFunction)) {
      leaves.push_back(call);
      continue;
    }
    // Slice one deliberately rejects all exceptional frame restoration and all
    // unknown calls, including CheckCurrentFrame-style hooks.
    if (isDirectCallTo(*call, setCurrentFrameFunction)) return false;
    return false;
  }
  return true;
}

bool validateRegionFrom(
    BasicBlock& block,
    Instruction* firstInstruction,
    CallInst& enter,
    CallInst& leave,
    DenseMap<BasicBlock*, RegionState>& states) {
  auto found = states.find(&block);
  if (found != states.end()) {
    // A cycle inside the dynamic frame might execute forever without leaving it.
    // Reject it rather than attempting a path-sensitive loop proof.
    return found->second == RegionState::Valid;
  }
  states[&block] = RegionState::Visiting;

  for (Instruction* instruction = firstInstruction; instruction != nullptr;
       instruction = instruction->getNextNode()) {
    if (instruction == &leave) {
      states[&block] = RegionState::Valid;
      return true;
    }
    if (instruction == &enter) return false;
    if (isa<DbgInfoIntrinsic>(instruction)) continue;
    if (auto* intrinsic = dyn_cast<IntrinsicInst>(instruction)) {
      const Intrinsic::ID id = intrinsic->getIntrinsicID();
      if (id == Intrinsic::lifetime_start || id == Intrinsic::lifetime_end) continue;
    }
    if (isa<CallBase>(instruction) || instruction->isEHPad() ||
        isa<ResumeInst>(instruction) || isa<ReturnInst>(instruction)) {
      states[&block] = RegionState::Invalid;
      return false;
    }
    if (auto* load = dyn_cast<LoadInst>(instruction)) {
      // Loading a managed/reference pointer could make the removed frame the
      // only root. Atomic/volatile and globally-backed loads may observe ARC
      // publication or the runtime's current-frame TLS state.
      const Type* type = load->getType();
      const Value* root = GetUnderlyingObject(load->getPointerOperand(),
                                              enter.getModule()->getDataLayout());
      if (load->isVolatile() || load->isAtomic() ||
          !(type->isIntegerTy() || type->isFloatingPointTy()) ||
          root == nullptr || isa<GlobalValue>(root)) {
        states[&block] = RegionState::Invalid;
        return false;
      }
      continue;
    }
    if (isa<StoreInst>(instruction) || isa<FenceInst>(instruction) ||
        isa<AtomicRMWInst>(instruction) || isa<AtomicCmpXchgInst>(instruction) ||
        isa<AllocaInst>(instruction) || instruction->mayReadOrWriteMemory()) {
      states[&block] = RegionState::Invalid;
      return false;
    }
    if (isa<UnreachableInst>(instruction)) {
      // LLVM unreachable terminates a path with no defined continuation. It
      // therefore has no observable frame cleanup requirement.
      states[&block] = RegionState::Valid;
      return true;
    }
    if (!instruction->isTerminator()) continue;

    if (succ_empty(&block)) {
      states[&block] = RegionState::Invalid;
      return false;
    }
    for (BasicBlock* successor : successors(&block)) {
      if (!validateRegionFrom(*successor, &*successor->begin(), enter, leave, states)) {
        states[&block] = RegionState::Invalid;
        return false;
      }
    }
    states[&block] = RegionState::Valid;
    return true;
  }

  states[&block] = RegionState::Invalid;
  return false;
}

bool validateDynamicRegion(CallInst& enter, CallInst& leave) {
  if (enter.getFunction() != leave.getFunction()) return false;
  DominatorTree dominators(*enter.getFunction());
  if (!dominators.dominates(&enter, &leave)) return false;

  DenseMap<BasicBlock*, RegionState> states;
  Instruction* first = enter.getNextNode();
  if (first == nullptr) return false;
  return validateRegionFrom(*enter.getParent(), first, enter, leave, states);
}

bool findCandidate(
    CallInst& candidateEnter,
    const Function* enterFunction,
    const Function* leaveFunction,
    const Function* setCurrentFrameFunction,
    const DataLayout& dataLayout,
    FrameCandidate& result) {
  // Slice one is intentionally limited to compiler-generated Kotlin bodies.
  // Runtime/C++ ObjHolder frames use the same ABI but have different source-
  // level lifetime and unwinding contracts.
  if (candidateEnter.getFunction() == nullptr ||
      !candidateEnter.getFunction()->getName().startswith("kfun:")) {
    return false;
  }
  uint64_t parameters = 0;
  uint64_t count = 0;
  auto* root = dyn_cast<AllocaInst>(GetUnderlyingObject(candidateEnter.getArgOperand(0), dataLayout));
  if (root == nullptr || !root->isStaticAlloca()) return false;
  if (!readFrameShape(candidateEnter, parameters, count)) return false;
  if (!hasExactFrameStorage(*root, count)) return false;

  DenseSet<Value*> visited;
  SmallVector<CallInst*, 2> enters;
  SmallVector<CallInst*, 2> leaves;
  if (!auditFrameUses(*root, *root, dataLayout, enterFunction, leaveFunction,
                      setCurrentFrameFunction, visited, enters, leaves) ||
      enters.size() != 1 || leaves.size() != 1 || enters.front() != &candidateEnter) {
    return false;
  }

  uint64_t leaveParameters = 0;
  uint64_t leaveCount = 0;
  if (!readFrameShape(*leaves.front(), leaveParameters, leaveCount) ||
      parameters != leaveParameters || count != leaveCount ||
      !validateDynamicRegion(candidateEnter, *leaves.front())) {
    return false;
  }

  result = FrameCandidate{root, &candidateEnter, leaves.front(), parameters, count};
  return true;
}

void restorePreparedWrapper(Function* function) {
  if (function == nullptr) return;
  if (function->hasFnAttribute(kPreparedMarker)) {
    function->removeFnAttr(Attribute::NoInline);
    function->removeFnAttr(kPreparedMarker);
  }
  function->removeFnAttr(kInsertedUsedMarker);
}

bool hasSupportedUsed(const Module& module) {
  const GlobalVariable* used = module.getGlobalVariable("llvm.used", true);
  if (used == nullptr) return true;
  const auto* initializer = used->hasInitializer()
      ? dyn_cast<ConstantArray>(used->getInitializer()) : nullptr;
  const auto* arrayType = initializer == nullptr
      ? nullptr : dyn_cast<ArrayType>(initializer->getType());
  return initializer != nullptr && initializer->getNumOperands() != 0 &&
         arrayType != nullptr &&
         arrayType->getElementType() == Type::getInt8PtrTy(module.getContext()) &&
         used->getLinkage() == GlobalValue::AppendingLinkage &&
         used->getSection() == "llvm.metadata" && used->getAddressSpace() == 0 &&
         !used->isThreadLocal();
}

bool isAlreadyInUsed(const Module& module, const GlobalValue& value) {
  const GlobalVariable* used = module.getGlobalVariable("llvm.used", true);
  const auto* initializer = used == nullptr || !used->hasInitializer()
      ? nullptr : dyn_cast<ConstantArray>(used->getInitializer());
  if (initializer == nullptr) return false;
  for (const Use& operand : initializer->operands()) {
    if (operand.get()->stripPointerCasts() == &value) return true;
  }
  return false;
}

GlobalVariable* replaceUsed(
    Module& module,
    GlobalVariable* used,
    ArrayRef<Constant*> values) {
  if (values.empty()) {
    if (used != nullptr) used->eraseFromParent();
    return nullptr;
  }
  Type* elementType = Type::getInt8PtrTy(module.getContext());
  ArrayType* replacementType = ArrayType::get(elementType, values.size());
  Constant* replacementInitializer = ConstantArray::get(replacementType, values);
  auto* replacement = new GlobalVariable(
      module, replacementType, used == nullptr ? false : used->isConstant(),
      used == nullptr ? GlobalValue::AppendingLinkage : used->getLinkage(),
      replacementInitializer, used == nullptr ? "llvm.used" : "", used,
      used == nullptr ? GlobalVariable::NotThreadLocal : used->getThreadLocalMode(),
      used == nullptr ? 0 : used->getAddressSpace(),
      used != nullptr && used->isExternallyInitialized());
  if (used == nullptr) {
    replacement->setSection("llvm.metadata");
  } else {
    replacement->copyAttributesFrom(used);
    replacement->copyMetadata(used, 0);
    replacement->takeName(used);
    used->eraseFromParent();
  }
  return replacement;
}

void appendExactToUsed(Module& module, ArrayRef<GlobalValue*> values) {
  GlobalVariable* used = module.getGlobalVariable("llvm.used", true);
  SmallVector<Constant*, 16> entries;
  if (used != nullptr) {
    const auto* initializer = cast<ConstantArray>(used->getInitializer());
    for (const Use& operand : initializer->operands()) {
      entries.push_back(cast<Constant>(operand.get()));
    }
  }
  Type* i8Pointer = Type::getInt8PtrTy(module.getContext());
  for (GlobalValue* value : values) {
    entries.push_back(ConstantExpr::getPointerCast(value, i8Pointer));
  }
  (void)replaceUsed(module, used, entries);
}

void removeInsertedUsed(Module& module) {
  GlobalVariable* used = module.getGlobalVariable("llvm.used", true);
  auto* initializer = used == nullptr ? nullptr : dyn_cast<ConstantArray>(used->getInitializer());
  if (initializer == nullptr) return;
  DenseSet<Function*> marked;
  for (Function& function : module) {
    if (function.hasFnAttribute(kInsertedUsedMarker)) marked.insert(&function);
  }
  if (marked.empty()) return;
  DenseSet<Function*> removed;
  SmallVector<Constant*, 16> kept;
  for (const Use& operand : initializer->operands()) {
    auto* value = cast<Constant>(operand.get());
    auto* function = dyn_cast<Function>(value->stripPointerCasts());
    if (function != nullptr && marked.count(function) && removed.insert(function).second) {
      continue;
    }
    kept.push_back(value);
  }
  (void)replaceUsed(module, used, kept);
}

bool isExactLeaveShapeGuard(const CallInst& call, const Function& leaveFunction) {
  if (!call.hasFnAttr(kLeaveShapeGuardCallMarker) || call.arg_size() != 2 ||
      leaveFunction.arg_size() != 3 ||
      call.getArgOperand(0) != leaveFunction.getArg(1) ||
      call.getArgOperand(1) != leaveFunction.getArg(2) ||
      !call.doesNotThrow()) {
    return false;
  }
  const auto* assembly = dyn_cast<InlineAsm>(call.getCalledOperand());
  if (assembly == nullptr || !assembly->hasSideEffects() ||
      assembly->getAsmString() != "" || assembly->getConstraintString() != "r,r" ||
      assembly->getDialect() != InlineAsm::AD_ATT) {
    return false;
  }
  const FunctionType* type = assembly->getFunctionType();
  return !type->isVarArg() && type->getReturnType()->isVoidTy() &&
         type->getNumParams() == 2 && type->getParamType(0)->isIntegerTy(32) &&
         type->getParamType(1)->isIntegerTy(32);
}

void removeLeaveShapeGuard(Module& module) {
  Function* leaveFunction = module.getFunction(kLeaveFrameName);
  if (leaveFunction == nullptr ||
      !leaveFunction->hasFnAttribute(kLeaveShapeGuardFunctionMarker)) {
    return;
  }
  SmallVector<CallInst*, 1> guards;
  for (BasicBlock& block : *leaveFunction) {
    for (Instruction& instruction : block) {
      auto* call = dyn_cast<CallInst>(&instruction);
      if (call != nullptr && call->hasFnAttr(kLeaveShapeGuardCallMarker)) {
        if (!isExactLeaveShapeGuard(*call, *leaveFunction)) {
          report_fatal_error("corrupt ARC LeaveFrame shape guard");
        }
        guards.push_back(call);
      }
    }
  }
  if (guards.size() != 1) {
    report_fatal_error("missing or duplicate ARC LeaveFrame shape guard");
  }
  for (CallInst* guard : guards) guard->eraseFromParent();
  leaveFunction->removeFnAttr(kLeaveShapeGuardFunctionMarker);
}

void restorePreparationState(Module& module) {
  removeLeaveShapeGuard(module);
  removeInsertedUsed(module);
  for (Function& function : module) restorePreparedWrapper(&function);
}

void collectFrameStorageUsers(
    Value& value,
    DenseSet<Value*>& visited,
    SmallVectorImpl<Instruction*>& leaves,
    SmallVectorImpl<Instruction*>& structural) {
  if (!visited.insert(&value).second) return;
  for (User* user : value.users()) {
    if (auto* cast = dyn_cast<BitCastInst>(user)) {
      collectFrameStorageUsers(*cast, visited, leaves, structural);
      structural.push_back(cast);
    } else if (auto* gep = dyn_cast<GetElementPtrInst>(user)) {
      collectFrameStorageUsers(*gep, visited, leaves, structural);
      structural.push_back(gep);
    } else if (auto* instruction = dyn_cast<Instruction>(user)) {
      // The candidate use audit has already proven that every remaining leaf
      // is zero-initialization, lifetime, or debug-only frame setup.
      leaves.push_back(instruction);
    }
  }
}

void eraseDeadFrameStorage(FrameCandidate& candidate) {
  DenseSet<Value*> visited;
  SmallVector<Instruction*, 16> leaves;
  SmallVector<Instruction*, 16> structural;
  collectFrameStorageUsers(*candidate.alloca, visited, leaves, structural);
  for (Instruction* instruction : leaves) {
    bool verified = false;
    if (auto* memset = dyn_cast<MemSetInst>(instruction)) {
      verified = isZeroMemsetOfRoot(*memset, *candidate.alloca,
                                    candidate.alloca->getModule()->getDataLayout());
    } else if (auto* intrinsic = dyn_cast<IntrinsicInst>(instruction)) {
      const Intrinsic::ID id = intrinsic->getIntrinsicID();
      verified = id == Intrinsic::lifetime_start || id == Intrinsic::lifetime_end;
    }
    if (!verified) {
      report_fatal_error("unverified ARC frame storage cleanup instruction");
    }
    instruction->eraseFromParent();
  }
  // DFS records derived pointers after their users, so this order deletes
  // children before parents without requiring another optimization pipeline.
  for (Instruction* instruction : structural) {
    if (instruction->getParent() != nullptr && instruction->use_empty()) {
      instruction->eraseFromParent();
    }
  }
  if (candidate.alloca->getParent() != nullptr && candidate.alloca->use_empty()) {
    candidate.alloca->eraseFromParent();
  }
}

unsigned inlinePreparedWrapper(Function* function) {
  if (function == nullptr || function->isDeclaration()) return 0;
  SmallVector<CallInst*, 32> calls;
  for (User* user : function->users()) {
    auto* call = dyn_cast<CallInst>(user);
    if (call != nullptr && isDirectCallTo(*call, function) &&
        !call->isMustTailCall() && call->getNumOperandBundles() == 0) {
      calls.push_back(call);
    }
  }
  for (CallInst* call : calls) {
    InlineFunctionInfo info;
    InlineResult result = InlineFunction(*call, info);
    if (!result.isSuccess()) {
      // Keep the valid call intact; the driver reports it as an inline failure.
      continue;
    }
  }
  unsigned failures = 0;
  for (User* user : function->users()) {
    auto* call = dyn_cast<CallBase>(user);
    if (call != nullptr && isDirectCallTo(*call, function)) {
      ++failures;
    }
  }
  return failures;
}

} // namespace

int LLVMKotlinPrepareArcFrameElision(LLVMModuleRef moduleRef) {
  Module& module = *unwrap(moduleRef);
  bool hasPrivateState = false;
  for (Function& function : module) {
    hasPrivateState = hasPrivateState || function.hasFnAttribute(kPreparedMarker) ||
        function.hasFnAttribute(kInsertedUsedMarker) ||
        function.hasFnAttribute(kLeaveShapeGuardFunctionMarker);
  }
  if (hasPrivateState) restorePreparationState(module);
  if (!hasSupportedUsed(module)) return 0;
  Function* enterFunction = module.getFunction(kEnterFrameName);
  Function* leaveFunction = module.getFunction(kLeaveFrameName);
  if (enterFunction == nullptr || leaveFunction == nullptr ||
      enterFunction->isDeclaration() || leaveFunction->isDeclaration() ||
      enterFunction->hasFnAttribute(Attribute::NoInline) ||
      leaveFunction->hasFnAttribute(Attribute::NoInline) ||
      enterFunction->hasFnAttribute(kPreparedMarker) ||
      leaveFunction->hasFnAttribute(kPreparedMarker)) {
    return 0;
  }
  for (const char* name : {kEnterFrameName, kLeaveFrameName, kSetCurrentFrameName}) {
    Function* function = module.getFunction(name);
    if (function == nullptr || function->hasFnAttribute(Attribute::NoInline) ||
        function->hasFnAttribute(kPreparedMarker)) {
      continue;
    }
    function->addFnAttr(Attribute::NoInline);
    function->addFnAttr(kPreparedMarker);
  }
  return 1;
}

int LLVMKotlinSealArcFrameElisionForLTO(LLVMModuleRef moduleRef) {
  Module& module = *unwrap(moduleRef);
  if (!hasSupportedUsed(module)) return 0;
  SmallVector<Function*, 3> wrappers;
  for (const char* name : {kEnterFrameName, kLeaveFrameName, kSetCurrentFrameName}) {
    Function* function = module.getFunction(name);
    if (function == nullptr || !function->hasFnAttribute(kPreparedMarker) ||
        !function->hasFnAttribute(Attribute::NoInline)) {
      return 0;
    }
    wrappers.push_back(function);
  }
  Function* leaveFunction = module.getFunction(kLeaveFrameName);
  if (leaveFunction == nullptr || leaveFunction->isDeclaration() ||
      leaveFunction->arg_size() != 3 ||
      !leaveFunction->getReturnType()->isVoidTy() ||
      !leaveFunction->getArg(1)->getType()->isIntegerTy(32) ||
      !leaveFunction->getArg(2)->getType()->isIntegerTy(32) ||
      leaveFunction->hasFnAttribute(kLeaveShapeGuardFunctionMarker) ||
      leaveFunction->empty()) {
    return 0;
  }
  for (BasicBlock& block : *leaveFunction) {
    for (Instruction& instruction : block) {
      auto* call = dyn_cast<CallBase>(&instruction);
      if (call != nullptr && call->hasFnAttr(kLeaveShapeGuardCallMarker)) return 0;
    }
  }
  auto insertionPoint = leaveFunction->getEntryBlock().getFirstInsertionPt();
  if (insertionPoint == leaveFunction->getEntryBlock().end()) return 0;

  LLVMContext& llvmContext = module.getContext();
  Type* i32 = Type::getInt32Ty(llvmContext);
  FunctionType* guardType = FunctionType::get(
      Type::getVoidTy(llvmContext), {i32, i32}, false);
  InlineAsm* assembly = InlineAsm::get(
      guardType, "", "r,r", true, false, InlineAsm::AD_ATT);
  IRBuilder<> builder(&*insertionPoint);
  CallInst* guard = builder.CreateCall(
      guardType, assembly, {leaveFunction->getArg(1), leaveFunction->getArg(2)});
  guard->setDoesNotThrow();
  guard->addAttribute(
      AttributeList::FunctionIndex,
      Attribute::get(llvmContext, kLeaveShapeGuardCallMarker));
  leaveFunction->addFnAttr(kLeaveShapeGuardFunctionMarker);
  SmallVector<GlobalValue*, 3> inserted;
  for (Function* wrapper : wrappers) {
    if (isAlreadyInUsed(module, *wrapper)) continue;
    wrapper->addFnAttr(kInsertedUsedMarker);
    inserted.push_back(wrapper);
  }
  if (!inserted.empty()) appendExactToUsed(module, inserted);
  return 1;
}

void LLVMKotlinRestoreArcFrameElision(LLVMModuleRef moduleRef) {
  Module& module = *unwrap(moduleRef);
  restorePreparationState(module);
}

int LLVMKotlinRemoveEmptyArcFrames(LLVMModuleRef moduleRef) {
  Module& module = *unwrap(moduleRef);
  Function* enterFunction = module.getFunction(kEnterFrameName);
  Function* leaveFunction = module.getFunction(kLeaveFrameName);
  Function* setCurrentFrameFunction = module.getFunction(kSetCurrentFrameName);

  // Never transform a module unless this invocation prepared both exact ABI
  // wrappers. This prevents a standalone/remove-only call from treating
  // already-inlined or user-shaped functions as compiler frame boundaries.
  const bool wrappersPrepared =
      enterFunction != nullptr && leaveFunction != nullptr &&
      enterFunction->hasFnAttribute(kPreparedMarker) &&
      leaveFunction->hasFnAttribute(kPreparedMarker) &&
      enterFunction->hasFnAttribute(Attribute::NoInline) &&
      leaveFunction->hasFnAttribute(Attribute::NoInline);
  if (!wrappersPrepared) {
    restorePreparationState(module);
    return 0;
  }

  SmallVector<CallInst*, 32> enterCalls;
  if (enterFunction != nullptr && leaveFunction != nullptr) {
    for (User* user : enterFunction->users()) {
      auto* call = dyn_cast<CallInst>(user);
      if (call != nullptr && isDirectCallTo(*call, enterFunction)) enterCalls.push_back(call);
    }
  }

  SmallVector<FrameCandidate, 16> candidates;
  const DataLayout& dataLayout = module.getDataLayout();
  for (CallInst* enter : enterCalls) {
    FrameCandidate candidate;
    if (findCandidate(*enter, enterFunction, leaveFunction, setCurrentFrameFunction,
                      dataLayout, candidate)) {
      candidates.push_back(candidate);
    }
  }

  for (FrameCandidate& candidate : candidates) {
    candidate.enter->eraseFromParent();
    candidate.leave->eraseFromParent();
    eraseDeadFrameStorage(candidate);
  }

  // Never allow the temporary preservation mechanism to change retained-frame
  // ABI. Restore the original attributes before explicitly recreating LTO's
  // normal wrapper-inlined final form below.
  restorePreparationState(module);
  // LTO ran while the exact wrappers were temporarily noinline. Recreate its
  // original final form for every retained direct call after empty frames are
  // gone. Address-taken uses remain untouched.
  (void)inlinePreparedWrapper(enterFunction);
  (void)inlinePreparedWrapper(leaveFunction);
  (void)inlinePreparedWrapper(setCurrentFrameFunction);
  return static_cast<int>(candidates.size());
}

int LLVMKotlinCountDirectArcFrameWrapperCalls(LLVMModuleRef moduleRef) {
  Module& module = *unwrap(moduleRef);
  unsigned count = 0;
  for (const char* name : {kEnterFrameName, kLeaveFrameName, kSetCurrentFrameName}) {
    Function* function = module.getFunction(name);
    if (function == nullptr) continue;
    for (User* user : function->users()) {
      auto* call = dyn_cast<CallBase>(user);
      if (call != nullptr && isDirectCallTo(*call, function)) ++count;
    }
  }
  return static_cast<int>(count);
}
