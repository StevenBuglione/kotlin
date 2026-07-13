/*
 * Copyright 2010-2026 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

#include <ArcReturnUpdateCoalescing.h>

#include <limits>

#include <llvm/Analysis/ValueTracking.h>
#include <llvm/IR/Function.h>
#include <llvm/IR/IntrinsicInst.h>
#include <llvm/IR/Instructions.h>
#include <llvm/IR/Module.h>
#include <llvm/Support/CBindingWrapping.h>

using namespace llvm;

namespace {

constexpr const char* kUpdateReturnRefRelaxedName = "UpdateReturnRefRelaxed";

Function* directCallee(const CallBase& call) {
  Value* called = call.getCalledOperand();
  return dyn_cast<Function>(called == nullptr ? nullptr : called->stripPointerCasts());
}

bool isExactReturnUpdate(const Instruction& instruction, const Function* updateReturnRef) {
  const auto* call = dyn_cast<CallInst>(&instruction);
  return call != nullptr && !call->isInlineAsm() && !call->hasOperandBundles() &&
         !call->isConvergent() && !call->cannotDuplicate() && call->arg_size() == 2 &&
         directCallee(*call) == updateReturnRef;
}

bool isIdenticalReturnUpdate(const Instruction& first, const Instruction& second,
                             const Function* updateReturnRef) {
  if (!isExactReturnUpdate(first, updateReturnRef) ||
      !isExactReturnUpdate(second, updateReturnRef)) {
    return false;
  }
  const auto& firstCall = cast<CallInst>(first);
  const auto& secondCall = cast<CallInst>(second);
  return firstCall.getArgOperand(0) == secondCall.getArgOperand(0) &&
         firstCall.getArgOperand(1) == secondCall.getArgOperand(1);
}

bool isTransparentSeparator(const Instruction& instruction,
                            const Instruction* previousUpdate) {
  if (isa<DbgInfoIntrinsic>(instruction)) return true;

  // Lifetime markers describe stack-storage validity to LLVM but do not execute an
  // ownership operation. Treat one as inert only when ValueTracking proves that it
  // names a distinct local allocation rather than either update operand.
  if (const auto* intrinsic = dyn_cast<IntrinsicInst>(&instruction)) {
    if (intrinsic->getIntrinsicID() == Intrinsic::lifetime_start ||
        intrinsic->getIntrinsicID() == Intrinsic::lifetime_end) {
      const auto* update = dyn_cast_or_null<CallInst>(previousUpdate);
      if (update == nullptr) return false;
      const DataLayout& dataLayout = instruction.getModule()->getDataLayout();
      const Value* lifetimeRoot =
          GetUnderlyingObject(intrinsic->getArgOperand(1), dataLayout);
      return isa<AllocaInst>(lifetimeRoot) &&
             lifetimeRoot != GetUnderlyingObject(update->getArgOperand(0), dataLayout) &&
             lifetimeRoot != GetUnderlyingObject(update->getArgOperand(1), dataLayout);
    }
  }

  // Keep the proof local to one basic block, but do not discard it for SSA-only
  // calculations or ordinary non-volatile reads. Neither can replace the result
  // slot or perform an ARC operation. Calls remain barriers even when annotated
  // readnone: their Kotlin ownership semantics are not encoded by LLVM attributes.
  return !isa<CallBase>(instruction) && !instruction.mayWriteToMemory() &&
         !instruction.mayHaveSideEffects();
}

} // namespace

int LLVMKotlinCoalesceAdjacentArcReturnUpdates(LLVMModuleRef moduleRef) {
  Module* module = unwrap(moduleRef);
  Function* updateReturnRef = module->getFunction(kUpdateReturnRefRelaxedName);
  if (updateReturnRef == nullptr) return 0;

  uint64_t removed = 0;
  for (Function& function : *module) {
    if (function.isDeclaration()) continue;
    for (BasicBlock& block : function) {
      Instruction* previousUpdate = nullptr;
      for (auto iterator = block.begin(); iterator != block.end();) {
        Instruction* current = &*iterator++;
        if (isExactReturnUpdate(*current, updateReturnRef)) {
          if (previousUpdate != nullptr &&
              isIdenticalReturnUpdate(*previousUpdate, *current, updateReturnRef)) {
            current->eraseFromParent();
            ++removed;
            // Keep the first call as the predecessor so runs of three or more collapse to one.
            continue;
          }
          previousUpdate = current;
          continue;
        }
        if (isTransparentSeparator(*current, previousUpdate)) {
          continue;
        }
        previousUpdate = nullptr;
      }
    }
  }
  return removed > static_cast<uint64_t>(std::numeric_limits<int>::max())
             ? std::numeric_limits<int>::max()
             : static_cast<int>(removed);
}
