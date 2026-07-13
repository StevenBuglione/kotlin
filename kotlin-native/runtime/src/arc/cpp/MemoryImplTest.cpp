/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

#include "Memory.h"

#include <algorithm>
#include <array>
#include <atomic>
#include <thread>
#include <vector>

#include "Exceptions.h"
#include "FinalizerHooks.hpp"
#include "gtest/gtest.h"
#include "MemorySharedRefs.hpp"
#include "Natives.h"
#include "ObjectTestSupport.hpp"
#include "PointerBits.h"
#include "TestSupport.hpp"
#include "MemoryPrivate.hpp"

#if !defined(KONAN_ARC_MEMORY_MANAGER) || KONAN_ARC_MEMORY_MANAGER != 1
#error "ARC runtime tests must use the dedicated ARC compile-time configuration"
#endif

TEST(ArcMemoryModelTest, HasDedicatedRuntimeIdentity) {
    EXPECT_EQ(CurrentMemoryModel, MemoryModel::kArc);
    EXPECT_NE(CurrentMemoryModel, MemoryModel::kStrict);
    EXPECT_NE(CurrentMemoryModel, MemoryModel::kRelaxed);
    EXPECT_NE(CurrentMemoryModel, MemoryModel::kExperimental);
}

TEST(ArcThreadStateTest, TracksNativeAndRunnableTransitionsWithoutTracingGC) {
    kotlin::RunInNewThread([](MemoryState* memoryState) {
        EXPECT_EQ(kotlin::GetThreadState(memoryState), kotlin::ThreadState::kRunnable);
        kotlin::AssertThreadState(memoryState, kotlin::ThreadState::kRunnable);

        EXPECT_EQ(
                kotlin::SwitchThreadState(memoryState, kotlin::ThreadState::kNative),
                kotlin::ThreadState::kRunnable);
        EXPECT_EQ(kotlin::GetThreadState(memoryState), kotlin::ThreadState::kNative);
        kotlin::AssertThreadState(
                memoryState,
                {kotlin::ThreadState::kRunnable, kotlin::ThreadState::kNative});
        EXPECT_EQ(
                kotlin::SwitchThreadState(memoryState, kotlin::ThreadState::kNative, /* reentrant = */ true),
                kotlin::ThreadState::kNative);

        Kotlin_mm_switchThreadStateRunnable();
        EXPECT_EQ(kotlin::GetThreadState(memoryState), kotlin::ThreadState::kRunnable);
    });
}

TEST(ArcThreadStateTest, CalledFromNativeGuardRegistersDetachedThreadAndRestoresNativeState) {
    kotlin::ScopedThread([] {
        ASSERT_FALSE(kotlin::mm::IsCurrentThreadRegistered());
        {
            kotlin::CalledFromNativeGuard guard;
            ASSERT_TRUE(kotlin::mm::IsCurrentThreadRegistered());
            EXPECT_EQ(kotlin::GetThreadState(), kotlin::ThreadState::kRunnable);
            {
                kotlin::CalledFromNativeGuard nestedGuard(/* reentrant = */ true);
                EXPECT_EQ(kotlin::GetThreadState(), kotlin::ThreadState::kRunnable);
            }
            EXPECT_EQ(kotlin::GetThreadState(), kotlin::ThreadState::kRunnable);
        }
        EXPECT_TRUE(kotlin::mm::IsCurrentThreadRegistered());
        EXPECT_EQ(kotlin::GetThreadState(), kotlin::ThreadState::kNative);
    });
}

TEST(ArcThreadStateDeathTest, RejectsNonReentrantSameStateTransition) {
    EXPECT_DEATH(
            kotlin::RunInNewThread([](MemoryState* memoryState) {
                kotlin::SwitchThreadState(memoryState, kotlin::ThreadState::kRunnable);
            }),
            "Illegal ARC thread state switch");
}

TEST(ArcReferenceCountStateTest, FinalReleaseAtomicallyEntersPermanentDeallocatingState) {
    ContainerHeader header{};
    header.setRefCount(1);
    EXPECT_EQ(header.decRefCount<true>(), 0);
    EXPECT_TRUE(header.arcDeallocating());
    EXPECT_FALSE(header.tryIncRefCount<true>());
}

TEST(ArcReferenceCountStateTest, ConcurrentRetainReleasePreservesTheAnchoredOwner) {
    constexpr int kThreads = 8;
    constexpr int kIterations = 10'000;
    ContainerHeader header{};
    header.setRefCount(1);
    std::atomic<bool> start = false;
    std::atomic<bool> invalidTransition = false;
    std::vector<std::thread> workers;
    for (int worker = 0; worker < kThreads; ++worker) {
        workers.emplace_back([&] {
            while (!start.load(std::memory_order_acquire)) {
            }
            for (int iteration = 0; iteration < kIterations; ++iteration) {
                header.incRefCount<true>();
                if (header.decRefCount<true>() <= 0) {
                    invalidTransition.store(true, std::memory_order_relaxed);
                    return;
                }
            }
        });
    }

    start.store(true, std::memory_order_release);
    for (auto& worker : workers) worker.join();

    EXPECT_FALSE(invalidTransition.load(std::memory_order_relaxed));
    EXPECT_EQ(header.refCount(), 1);
    EXPECT_EQ(header.decRefCount<true>(), 0);
    EXPECT_TRUE(header.arcDeallocating());
}

TEST(ArcDeinitMarkerStateTest, UninitializedMarkerReadsNullWithoutChangingContainerState) {
    ContainerHeader header{};
    header.setRefCount(1);

    EXPECT_EQ(header.takeArcInitializedDeinitType(), nullptr);
    EXPECT_EQ(header.takeArcInitializedDeinitType(), nullptr);
    EXPECT_EQ(header.refCount(), 1);
    EXPECT_FALSE(header.arcDeallocating());
}

TEST(ArcDeinitMarkerStateTest, InitializedMarkerIsTakenExactlyOnceByConcurrentConsumers) {
    constexpr int kThreads = 8;
    ContainerHeader header{};
    header.setRefCount(1);
    header.setArcInitializedDeinitType(theAnyTypeInfo);
    std::atomic<bool> start = false;
    std::atomic<int> successfulTakes = 0;
    std::atomic<int> unexpectedValues = 0;
    std::vector<std::thread> workers;
    for (int worker = 0; worker < kThreads; ++worker) {
        workers.emplace_back([&] {
            while (!start.load(std::memory_order_acquire)) {
            }
            const TypeInfo* marker = header.takeArcInitializedDeinitType();
            if (marker == theAnyTypeInfo) {
                successfulTakes.fetch_add(1, std::memory_order_relaxed);
            } else if (marker != nullptr) {
                unexpectedValues.fetch_add(1, std::memory_order_relaxed);
            }
        });
    }

    start.store(true, std::memory_order_release);
    for (auto& worker : workers) worker.join();

    EXPECT_EQ(successfulTakes.load(std::memory_order_relaxed), 1);
    EXPECT_EQ(unexpectedValues.load(std::memory_order_relaxed), 0);
    EXPECT_EQ(header.takeArcInitializedDeinitType(), nullptr);
    EXPECT_EQ(header.refCount(), 1);
    EXPECT_FALSE(header.arcDeallocating());
}

TEST(ArcReferenceCountStateDeathTest, GeneralRetainCannotCreateInitialOwnership) {
    ContainerHeader header{};
    EXPECT_DEATH(header.incRefCount<true>(), "Attempted to retain a zero-count or deallocating ARC object");
}

namespace {

struct Payload {
    static constexpr std::array<ObjHeader* Payload::*, 0> kFields{};
};

struct NodePayload {
    ObjHeader* next = nullptr;
    static constexpr std::array<ObjHeader* NodePayload::*, 1> kFields{&NodePayload::next};
};

struct WeakCounterPayload {
    ObjHeader* referred = nullptr;
    KInt lock = 0;
    KInt cookie = 0;
    static constexpr std::array<ObjHeader* WeakCounterPayload::*, 0> kFields{};
};

struct RecycledPayload {
    ObjHeader* reference = nullptr;
    uint64_t marker = 0;
    static constexpr std::array<ObjHeader* RecycledPayload::*, 1> kFields{&RecycledPayload::reference};
};

constexpr size_t kReleaseVisibilityOwnerCount = 8;

struct ReleaseVisibilityPayload {
    std::array<uint32_t, kReleaseVisibilityOwnerCount> writes{};
    static constexpr std::array<ObjHeader* ReleaseVisibilityPayload::*, 0> kFields{};
};

using Object = kotlin::test_support::Object<Payload>;
using Node = kotlin::test_support::Object<NodePayload>;
using WeakCounter = kotlin::test_support::Object<WeakCounterPayload>;
using RecycledObject = kotlin::test_support::Object<RecycledPayload>;
using ReleaseVisibilityObject = kotlin::test_support::Object<ReleaseVisibilityPayload>;

void recycledArcDestroy(ObjHeader* object);
void releaseVisibilityArcDestroy(ObjHeader* object);

struct FrameStorage {
    FrameOverlay overlay{};
    ObjHeader* parameter = nullptr;
    ObjHeader* local = nullptr;

    ObjHeader** start() { return reinterpret_cast<ObjHeader**>(&overlay); }
    static constexpr int kParameters = 1;
};

static_assert(sizeof(FrameStorage) % sizeof(void*) == 0);
constexpr int kFrameStorageCount = sizeof(FrameStorage) / sizeof(void*);

struct MultiLocalFrameStorage {
    FrameOverlay overlay{};
    ObjHeader* parameter = nullptr;
    ObjHeader* first = nullptr;
    ObjHeader* second = nullptr;

    ObjHeader** start() { return reinterpret_cast<ObjHeader**>(&overlay); }
    static constexpr int kParameters = 1;
};

static_assert(sizeof(MultiLocalFrameStorage) % sizeof(void*) == 0);
constexpr int kMultiLocalFrameStorageCount = sizeof(MultiLocalFrameStorage) / sizeof(void*);

kotlin::test_support::TypeInfoHolder permanentTypeInfo{
        kotlin::test_support::TypeInfoHolder::ObjectBuilder<Payload>()};
Object permanentObject(permanentTypeInfo.typeInfo());
kotlin::test_support::TypeInfoHolder nodeTypeInfo{
        kotlin::test_support::TypeInfoHolder::ObjectBuilder<NodePayload>().addFlag(TF_HAS_FINALIZER)};
kotlin::test_support::TypeInfoHolder weakCounterTypeInfo{
        kotlin::test_support::TypeInfoHolder::ObjectBuilder<WeakCounterPayload>()};
kotlin::test_support::TypeInfoHolder recycledTypeInfo{
        kotlin::test_support::TypeInfoHolder::ObjectBuilder<RecycledPayload>()
                .addFlag(TF_HAS_FINALIZER)
                .setArcDestroy(recycledArcDestroy)};
kotlin::test_support::TypeInfoHolder releaseVisibilityTypeInfo{
        kotlin::test_support::TypeInfoHolder::ObjectBuilder<ReleaseVisibilityPayload>()
                .setArcDestroy(releaseVisibilityArcDestroy)};
kotlin::test_support::TypeInfoHolder recycledByteArrayTypeInfo{
        kotlin::test_support::TypeInfoHolder::ArrayBuilder<uint8_t>()};
std::atomic<int> finalizedNodes = 0;
std::atomic<int> finalizedRecycledObjects = 0;
std::atomic<int> recycledArcDeinitCount = 0;
std::atomic<bool> recycledArcDeinitSawInitializedPayload = false;
std::atomic<int> releaseVisibilityArcDeinitCount = 0;
std::atomic<uint32_t> releaseVisibilityObservedMask = 0;
std::atomic<bool> finalizerSawRegisteredRuntime = false;
std::atomic<FrameOverlay*> finalizerObservedFrame = nullptr;
std::atomic<bool> resurrectionRejected = false;
std::atomic<bool> weakWasZeroBeforeFinalizer = false;
std::atomic<ObjHeader*> weakCounterForFinalizer = nullptr;
std::vector<const char*> arcDeinitOrder;
std::atomic<bool> arcDeinitFieldWasAlive = false;
std::atomic<bool> arcDeinitResurrectionRejected = false;
ObjHeader* arcDeinitException = nullptr;
std::atomic<int> arcFieldDestroyThunkCalls = 0;
std::atomic<int> forgedLegacyMarkCallbackCalls = 0;

extern "C" OBJ_GETTER(Konan_WeakReferenceCounterLegacyMM_get, ObjHeader* counter);

ObjHeader* permanentHeader() {
    permanentObject.header()->typeInfoOrMeta_ =
            setPointerBits(permanentObject.header()->typeInfoOrMeta_, OBJECT_TAG_PERMANENT_CONTAINER);
    return permanentObject.header();
}

void countNodeFinalizer(ObjHeader* object) {
    if (object->type_info() == recycledTypeInfo.typeInfo()) {
        finalizedRecycledObjects.fetch_add(1, std::memory_order_relaxed);
        return;
    }
    if (object->type_info() != nodeTypeInfo.typeInfo()) return;
    finalizedNodes.fetch_add(1, std::memory_order_relaxed);
    finalizerSawRegisteredRuntime.store(kotlin::mm::IsCurrentThreadRegistered(), std::memory_order_relaxed);
    finalizerObservedFrame.store(getCurrentFrame(), std::memory_order_relaxed);
    resurrectionRejected.store(!TryAddHeapRef(object), std::memory_order_relaxed);
    if (ObjHeader* counter = weakCounterForFinalizer.load(std::memory_order_relaxed)) {
        ObjHeader* promoted = nullptr;
        Konan_WeakReferenceCounterLegacyMM_get(counter, &promoted);
        weakWasZeroBeforeFinalizer.store(promoted == nullptr, std::memory_order_relaxed);
        if (promoted != nullptr) ReleaseHeapRef(promoted);
    }
}

void recycledArcDestroy(ObjHeader* object) {
    auto& payload = *RecycledObject::FromObjHeader(object);
    recycledArcDeinitSawInitializedPayload.store(
            payload.reference == permanentHeader() && payload.marker == 0xfeedfacecafebeefULL,
            std::memory_order_relaxed);
    recycledArcDeinitCount.fetch_add(1, std::memory_order_relaxed);
}

void releaseVisibilityArcDestroy(ObjHeader* object) {
    auto& payload = *ReleaseVisibilityObject::FromObjHeader(object);
    uint32_t observedMask = 0;
    for (size_t index = 0; index < payload.writes.size(); ++index) {
        if (payload.writes[index] == static_cast<uint32_t>(index + 1)) observedMask |= uint32_t{1} << index;
    }
    releaseVisibilityObservedMask.store(observedMask, std::memory_order_relaxed);
    releaseVisibilityArcDeinitCount.fetch_add(1, std::memory_order_relaxed);
}

void baseArcDestroy(ObjHeader* object) {
    arcDeinitOrder.push_back("base");
    arcDeinitResurrectionRejected.store(!TryAddHeapRef(object), std::memory_order_relaxed);
}

void derivedArcDestroy(ObjHeader* object) {
    arcDeinitOrder.push_back("derived");
    arcDeinitResurrectionRejected.store(!TryAddHeapRef(object), std::memory_order_relaxed);
}

void firstFrameArcDestroy(ObjHeader*) {
    arcDeinitOrder.push_back("first");
}

void secondFrameArcDestroy(ObjHeader*) {
    arcDeinitOrder.push_back("second");
}

void derivedArcDestroyWithField(ObjHeader* object) {
    derivedArcDestroy(object);
    arcDeinitFieldWasAlive.store(Node::FromObjHeader(object)->next != nullptr, std::memory_order_relaxed);
}

void generatedArcFieldDestroyThunk(void*, ObjHeader* object) {
    arcFieldDestroyThunkCalls.fetch_add(1, std::memory_order_relaxed);
    ZeroHeapRef(&Node::FromObjHeader(object)->next);
}

void forgedLegacyMarkCallback(void*, ObjHeader*) {
    forgedLegacyMarkCallbackCalls.fetch_add(1, std::memory_order_relaxed);
}

void throwingArcDestroy(ObjHeader*) {
    throw std::runtime_error("exception escaped from ARC destroy hook");
}

void throwingKotlinArcDestroy(ObjHeader*) {
    ThrowException(arcDeinitException);
}

class ScopedNodeFinalizerHook {
public:
    ScopedNodeFinalizerHook() {
        finalizedNodes.store(0, std::memory_order_relaxed);
        finalizedRecycledObjects.store(0, std::memory_order_relaxed);
        recycledArcDeinitCount.store(0, std::memory_order_relaxed);
        recycledArcDeinitSawInitializedPayload.store(false, std::memory_order_relaxed);
        finalizerSawRegisteredRuntime.store(false, std::memory_order_relaxed);
        finalizerObservedFrame.store(nullptr, std::memory_order_relaxed);
        resurrectionRejected.store(false, std::memory_order_relaxed);
        weakWasZeroBeforeFinalizer.store(false, std::memory_order_relaxed);
        weakCounterForFinalizer.store(nullptr, std::memory_order_relaxed);
        kotlin::SetFinalizerHookForTesting(countNodeFinalizer);
    }

    ~ScopedNodeFinalizerHook() {
        kotlin::SetFinalizerHookForTesting(nullptr);
        weakCounterForFinalizer.store(nullptr, std::memory_order_relaxed);
    }
};

ObjHeader** nextSlot(ObjHeader* node) {
    return &Node::FromObjHeader(node)->next;
}

void installWeakCounter(ObjHeader* object, ObjHeader* counter) {
    WeakCounter::FromObjHeader(counter)->referred = object;
    MetaObjHeader* meta = object->meta_object();
    UpdateHeapRef(&meta->WeakReference.counter_, counter);
}

ObjHeader* allocateRecycledByteArray(int32_t elements, ObjHolder& holder) {
    return AllocArrayInstance(recycledByteArrayTypeInfo.typeInfo(), elements, holder.slot());
}

} // namespace

TEST(ArcFrameTest, NormalLeaveReleasesLocalsButNotBorrowedParameters) {
    kotlin::RunInNewThread([] {
        ASSERT_EQ(getCurrentFrame(), nullptr);
        FrameStorage frame;
        frame.parameter = permanentHeader();
        frame.local = permanentHeader();

        EnterFrame(frame.start(), FrameStorage::kParameters, kFrameStorageCount);
        EXPECT_EQ(getCurrentFrame(), &frame.overlay);
        LeaveFrame(frame.start(), FrameStorage::kParameters, kFrameStorageCount);

        EXPECT_EQ(getCurrentFrame(), nullptr);
        EXPECT_EQ(frame.parameter, permanentHeader());
        EXPECT_EQ(frame.local, nullptr);
    });
}

TEST(ArcFrameTest, NormalLeaveReleasesOwningLocalsInReverseSlotOrder) {
    kotlin::RunInNewThread([] {
        arcDeinitOrder.clear();
        kotlin::test_support::TypeInfoHolder firstType{
                kotlin::test_support::TypeInfoHolder::ObjectBuilder<Payload>().setArcDestroy(firstFrameArcDestroy)};
        kotlin::test_support::TypeInfoHolder secondType{
                kotlin::test_support::TypeInfoHolder::ObjectBuilder<Payload>().setArcDestroy(secondFrameArcDestroy)};
        ObjHolder firstOwner;
        ObjHolder secondOwner;
        ObjHeader* first = AllocInstance(firstType.typeInfo(), firstOwner.slot());
        ObjHeader* second = AllocInstance(secondType.typeInfo(), secondOwner.slot());
        Kotlin_ArcMarkDeinitInitialized(first, firstType.typeInfo());
        Kotlin_ArcMarkDeinitInitialized(second, secondType.typeInfo());

        MultiLocalFrameStorage frame;
        frame.parameter = permanentHeader();
        EnterFrame(frame.start(), MultiLocalFrameStorage::kParameters, kMultiLocalFrameStorageCount);
        UpdateStackRef(&frame.first, first);
        UpdateStackRef(&frame.second, second);
        firstOwner.clear();
        secondOwner.clear();

        LeaveFrame(frame.start(), MultiLocalFrameStorage::kParameters, kMultiLocalFrameStorageCount);

        EXPECT_EQ(frame.parameter, permanentHeader());
        EXPECT_EQ(frame.first, nullptr);
        EXPECT_EQ(frame.second, nullptr);
        ASSERT_EQ(arcDeinitOrder.size(), 2u);
        EXPECT_STREQ(arcDeinitOrder[0], "second");
        EXPECT_STREQ(arcDeinitOrder[1], "first");
    });
}

TEST(ArcFrameTest, CleanupLandingpadsReleaseTheirOwnFrames) {
    kotlin::RunInNewThread([] {
        FrameStorage outer;
        FrameStorage inner;
        for (FrameStorage* frame : {&outer, &inner}) {
            frame->parameter = permanentHeader();
            frame->local = permanentHeader();
            EnterFrame(frame->start(), FrameStorage::kParameters, kFrameStorageCount);
        }

        SetCurrentFrame(inner.start());
        LeaveFrame(inner.start(), FrameStorage::kParameters, kFrameStorageCount);
        SetCurrentFrame(outer.start());
        EXPECT_EQ(getCurrentFrame(), &outer.overlay);
        EXPECT_EQ(inner.local, nullptr);
        EXPECT_EQ(outer.local, permanentHeader());
        EXPECT_EQ(inner.parameter, permanentHeader());

        LeaveFrame(outer.start(), FrameStorage::kParameters, kFrameStorageCount);
        EXPECT_EQ(getCurrentFrame(), nullptr);
        EXPECT_EQ(outer.local, nullptr);
        EXPECT_EQ(outer.parameter, permanentHeader());
    });
}

TEST(ArcFrameTest, LeaveUnlinksBeforeReentrantDestructionAndDoesNotReleaseBorrowedParameters) {
    ScopedNodeFinalizerHook finalizers;
    kotlin::RunInNewThread([] {
        FrameStorage outer;
        FrameStorage inner;
        ObjHolder borrowedOwner;
        ObjHolder localOwner;
        ObjHeader* borrowed = AllocInstance(nodeTypeInfo.typeInfo(), borrowedOwner.slot());
        ObjHeader* local = AllocInstance(nodeTypeInfo.typeInfo(), localOwner.slot());

        EnterFrame(outer.start(), FrameStorage::kParameters, kFrameStorageCount);
        inner.parameter = borrowed;
        UpdateStackRef(&inner.local, local);
        localOwner.clear();
        EnterFrame(inner.start(), FrameStorage::kParameters, kFrameStorageCount);

        LeaveFrame(inner.start(), FrameStorage::kParameters, kFrameStorageCount);

        EXPECT_EQ(getCurrentFrame(), &outer.overlay);
        EXPECT_EQ(finalizerObservedFrame.load(std::memory_order_relaxed), &outer.overlay);
        EXPECT_EQ(finalizedNodes.load(std::memory_order_relaxed), 1);
        EXPECT_EQ(inner.local, nullptr);
        EXPECT_EQ(inner.parameter, borrowed);

        // The parameter was +0 and remains owned by the caller.
        borrowedOwner.clear();
        EXPECT_EQ(finalizedNodes.load(std::memory_order_relaxed), 2);
        LeaveFrame(outer.start(), FrameStorage::kParameters, kFrameStorageCount);
    });
}

TEST(ArcFrameDeathTest, RetargetRejectsSkippedOwningFrameWithoutInspectingItsStorage) {
    EXPECT_DEATH(
            kotlin::RunInNewThread([] {
                FrameStorage target;
                FrameStorage invalidated;
                EnterFrame(target.start(), FrameStorage::kParameters, kFrameStorageCount);
                EnterFrame(invalidated.start(), FrameStorage::kParameters, kFrameStorageCount);

                // Model an inner frame whose native stack storage is no longer safe to inspect.
                // SetCurrentFrame must diagnose using pointer identity only: these deliberately
                // invalid layout values must never be read while reporting the skipped cleanup.
                invalidated.overlay.parameters = INT32_MAX;
                invalidated.overlay.count = -1;
                invalidated.local = reinterpret_cast<ObjHeader*>(0x21);
                SetCurrentFrame(target.start());
            }),
            "ARC frame retarget expected current frame.*an owning ARC frame was skipped");
}

TEST(ArcDestructionTest, EagerlyDestroysAcyclicObjectsAtZeroCount) {
    ScopedNodeFinalizerHook finalizers;
    kotlin::RunInNewThread([] {
        ObjHolder root;
        ObjHolder child;
        ObjHeader* rootObject = AllocInstance(nodeTypeInfo.typeInfo(), root.slot());
        ObjHeader* childObject = AllocInstance(nodeTypeInfo.typeInfo(), child.slot());
        UpdateHeapRef(nextSlot(rootObject), childObject);
        child.clear();

        root.clear();
        EXPECT_EQ(finalizedNodes.load(std::memory_order_relaxed), 2);
    });
}

TEST(ArcDestructionTest, NewAllocationMovesInitialOwnershipIntoResultSlot) {
    ScopedNodeFinalizerHook finalizers;
    kotlin::RunInNewThread([] {
        ObjHolder result;
        AllocInstance(nodeTypeInfo.typeInfo(), result.slot());
        EXPECT_EQ(finalizedNodes.load(std::memory_order_relaxed), 0);

        AllocInstance(nodeTypeInfo.typeInfo(), result.slot());
        EXPECT_EQ(finalizedNodes.load(std::memory_order_relaxed), 1);

        result.clear();
        EXPECT_EQ(finalizedNodes.load(std::memory_order_relaxed), 2);
    });
}

TEST(ArcDestructionTest, MovesOwnedSourceSlotIntoInitializedHeapSlotWithoutRetain) {
    ScopedNodeFinalizerHook finalizers;
    kotlin::RunInNewThread([] {
        ObjHolder source;
        ObjHolder destination;
        ObjHeader* replacement = AllocInstance(nodeTypeInfo.typeInfo(), source.slot());
        ObjHeader* previous = AllocInstance(nodeTypeInfo.typeInfo(), destination.slot());
        ASSERT_NE(replacement, previous);

        MoveReferenceIntoHeapSlotArc(destination.slot(), source.slot(), replacement);

        EXPECT_EQ(source.obj(), nullptr);
        EXPECT_EQ(destination.obj(), replacement);
        EXPECT_EQ(finalizedNodes.load(std::memory_order_relaxed), 1);

        destination.clear();
        EXPECT_EQ(finalizedNodes.load(std::memory_order_relaxed), 2);
    });
}

TEST(ArcDestructionTest, OwnedHeapMoveBalancesTwoOwnersWhenDestinationAlreadyContainsSource) {
    ScopedNodeFinalizerHook finalizers;
    kotlin::RunInNewThread([] {
        ObjHolder source;
        ObjHolder destination;
        ObjHeader* object = AllocInstance(nodeTypeInfo.typeInfo(), source.slot());
        UpdateHeapRef(destination.slot(), object);

        MoveReferenceIntoHeapSlotArc(destination.slot(), source.slot(), object);

        EXPECT_EQ(source.obj(), nullptr);
        EXPECT_EQ(destination.obj(), object);
        EXPECT_EQ(finalizedNodes.load(std::memory_order_relaxed), 0);

        destination.clear();
        EXPECT_EQ(finalizedNodes.load(std::memory_order_relaxed), 1);
    });
}

TEST(ArcDestructionTest, OwnedHeapMoveTransfersNullableOwnershipAndReleasesOldDestination) {
    ScopedNodeFinalizerHook finalizers;
    kotlin::RunInNewThread([] {
        ObjHolder source;
        ObjHolder destination;
        AllocInstance(nodeTypeInfo.typeInfo(), destination.slot());

        MoveReferenceIntoHeapSlotArc(destination.slot(), source.slot(), nullptr);

        EXPECT_EQ(source.obj(), nullptr);
        EXPECT_EQ(destination.obj(), nullptr);
        EXPECT_EQ(finalizedNodes.load(std::memory_order_relaxed), 1);
    });
}

TEST(ArcDestructionTest, OwnedHeapMoveRejectsAValueNotOwnedByItsSourceSlot) {
    EXPECT_DEATH(
            kotlin::RunInNewThread([] {
                ObjHolder source;
                ObjHolder destination;
                ObjHeader* replacement = AllocInstance(nodeTypeInfo.typeInfo(), source.slot());
                AllocInstance(nodeTypeInfo.typeInfo(), destination.slot());
                MoveReferenceIntoHeapSlotArc(destination.slot(), source.slot(), destination.obj());
                // Keep the expected value visibly live so an optimizing test compiler cannot
                // discard the allocation whose identity the runtime assertion checks.
                EXPECT_NE(replacement, nullptr);
            }),
            "ARC owned reference transfer source slot does not own the object");
}

TEST(ArcReferenceCountOrderingTest, FinalDeinitAcquiresWritesFromEveryReleasingThread) {
    releaseVisibilityArcDeinitCount.store(0, std::memory_order_relaxed);
    releaseVisibilityObservedMask.store(0, std::memory_order_relaxed);
    kotlin::RunInNewThread([] {
        ObjHolder object;
        ObjHeader* allocated = AllocInstance(releaseVisibilityTypeInfo.typeInfo(), object.slot());
        for (size_t owner = 1; owner < kReleaseVisibilityOwnerCount; ++owner) {
            ASSERT_TRUE(TryAddHeapRef(allocated));
        }
        Kotlin_ArcMarkDeinitInitialized(allocated, releaseVisibilityTypeInfo.typeInfo());

        std::atomic<bool> start = false;
        std::vector<std::thread> workers;
        for (size_t index = 1; index < kReleaseVisibilityOwnerCount; ++index) {
            workers.emplace_back([&, index] {
                kotlin::RunInNewThread([&, index] {
                    while (!start.load(std::memory_order_acquire)) {
                    }
                    auto& payload = *ReleaseVisibilityObject::FromObjHeader(allocated);
                    payload.writes[index] = static_cast<uint32_t>(index + 1);
                    ReleaseHeapRef(allocated);
                });
            });
        }

        start.store(true, std::memory_order_release);
        auto& payload = *ReleaseVisibilityObject::FromObjHeader(allocated);
        payload.writes[0] = 1;
        object.clear();
        for (auto& worker : workers) worker.join();

        constexpr uint32_t kExpectedMask = (uint32_t{1} << kReleaseVisibilityOwnerCount) - 1;
        EXPECT_EQ(releaseVisibilityArcDeinitCount.load(std::memory_order_relaxed), 1);
        EXPECT_EQ(releaseVisibilityObservedMask.load(std::memory_order_relaxed), kExpectedMask);
    });
}

TEST(ArcRecyclingTest, ReusesExactSizeAfterCompleteFinalizationAndZeroesPayload) {
    ScopedNodeFinalizerHook finalizers;
    kotlin::RunInNewThread([] {
        uintptr_t firstAddress = 0;
        {
            ObjHolder first;
            ObjHeader* allocated = AllocInstance(recycledTypeInfo.typeInfo(), first.slot());
            firstAddress = reinterpret_cast<uintptr_t>(allocated);
            auto& payload = *RecycledObject::FromObjHeader(allocated);
            UpdateHeapRef(&payload.reference, permanentHeader());
            payload.marker = 0xfeedfacecafebeefULL;
            Kotlin_ArcMarkDeinitInitialized(allocated, recycledTypeInfo.typeInfo());

            first.clear();

            EXPECT_EQ(finalizedRecycledObjects.load(std::memory_order_relaxed), 1);
            EXPECT_EQ(recycledArcDeinitCount.load(std::memory_order_relaxed), 1);
            EXPECT_TRUE(recycledArcDeinitSawInitializedPayload.load(std::memory_order_relaxed));
        }

        ObjHolder second;
        ObjHeader* recycled = AllocInstance(recycledTypeInfo.typeInfo(), second.slot());
        EXPECT_EQ(reinterpret_cast<uintptr_t>(recycled), firstAddress);
        auto& recycledPayload = *RecycledObject::FromObjHeader(recycled);
        EXPECT_EQ(recycledPayload.reference, nullptr);
        EXPECT_EQ(recycledPayload.marker, 0u);

        UpdateHeapRef(&recycledPayload.reference, permanentHeader());
        recycledPayload.marker = 0xfeedfacecafebeefULL;
        Kotlin_ArcMarkDeinitInitialized(recycled, recycledTypeInfo.typeInfo());
        second.clear();

        EXPECT_EQ(finalizedRecycledObjects.load(std::memory_order_relaxed), 2);
        EXPECT_EQ(recycledArcDeinitCount.load(std::memory_order_relaxed), 2);
        EXPECT_TRUE(recycledArcDeinitSawInitializedPayload.load(std::memory_order_relaxed));
    });
}

TEST(ArcRecyclingTest, RetainsFourMixedExactSizesAcrossInterveningMisses) {
    const int physicalBefore = Kotlin_ArcAllocatedContainerCountForTests();
    kotlin::RunInNewThread([](MemoryState* state) {
        constexpr std::array<int32_t, 4> kSizes{7, 29, 83, 191};
        constexpr std::array<size_t, 4> kOrder{1, 3, 0, 2};
        std::array<ObjHolder, kSizes.size()> originals;
        std::array<uintptr_t, kSizes.size()> originalAddresses{};
        std::array<size_t, kSizes.size()> allocationSizes{};
        for (size_t index = 0; index < kSizes.size(); ++index) {
            ObjHeader* object = allocateRecycledByteArray(kSizes[index], originals[index]);
            originalAddresses[index] = reinterpret_cast<uintptr_t>(object);
            allocationSizes[index] = containerFor(object)->containerSize();
        }
        for (auto& original : originals) original.clear();
        ASSERT_EQ(Kotlin_ArcRecycledContainerCountForTests(state), kSizes.size());
        for (size_t index = 0; index < kSizes.size(); ++index) {
            EXPECT_EQ(Kotlin_ArcRecycledContainerSizeForTests(state, index), allocationSizes[kSizes.size() - 1 - index]);
        }

        std::array<ObjHolder, kSizes.size()> replacements;
        std::array<uintptr_t, kSizes.size()> replacementAddresses{};
        for (size_t position = 0; position < kOrder.size(); ++position) {
            const size_t index = kOrder[position];
            replacementAddresses[index] =
                    reinterpret_cast<uintptr_t>(allocateRecycledByteArray(kSizes[index], replacements[position]));
        }
        EXPECT_EQ(replacementAddresses, originalAddresses);
        EXPECT_EQ(Kotlin_ArcRecycledContainerCountForTests(state), 0u);
    });
    EXPECT_EQ(Kotlin_ArcAllocatedContainerCountForTests(), physicalBefore);
}

TEST(ArcRecyclingTest, RetainsFourDuplicateExactSizesForBurstReuse) {
    kotlin::RunInNewThread([] {
        constexpr int32_t kSize = 73;
        std::array<ObjHolder, 4> originals;
        std::array<uintptr_t, 4> originalAddresses{};
        for (size_t index = 0; index < originals.size(); ++index) {
            originalAddresses[index] =
                    reinterpret_cast<uintptr_t>(allocateRecycledByteArray(kSize, originals[index]));
        }
        for (auto& original : originals) original.clear();

        std::array<ObjHolder, 4> replacements;
        std::array<uintptr_t, 4> replacementAddresses{};
        for (size_t index = 0; index < replacements.size(); ++index) {
            replacementAddresses[index] =
                    reinterpret_cast<uintptr_t>(allocateRecycledByteArray(kSize, replacements[index]));
        }
        std::sort(originalAddresses.begin(), originalAddresses.end());
        std::sort(replacementAddresses.begin(), replacementAddresses.end());
        EXPECT_EQ(replacementAddresses, originalAddresses);
    });
}

TEST(ArcRecyclingTest, ExactSizeMissPreservesCachedEntry) {
    kotlin::RunInNewThread([](MemoryState* state) {
        const int physicalBefore = Kotlin_ArcAllocatedContainerCountForTests();
        ObjHolder exact;
        uintptr_t exactAddress = reinterpret_cast<uintptr_t>(allocateRecycledByteArray(41, exact));
        exact.clear();
        ASSERT_EQ(Kotlin_ArcRecycledContainerCountForTests(state), 1u);
        EXPECT_EQ(Kotlin_ArcAllocatedContainerCountForTests(), physicalBefore + 1);

        ObjHolder nearMiss;
        // Separate counts by more than container alignment so these cannot share one logical size.
        uintptr_t nearMissAddress = reinterpret_cast<uintptr_t>(allocateRecycledByteArray(57, nearMiss));
        EXPECT_NE(nearMissAddress, exactAddress);
        EXPECT_EQ(Kotlin_ArcRecycledContainerCountForTests(state), 1u);
        EXPECT_EQ(Kotlin_ArcAllocatedContainerCountForTests(), physicalBefore + 2);

        ObjHolder exactReplacement;
        EXPECT_EQ(reinterpret_cast<uintptr_t>(allocateRecycledByteArray(41, exactReplacement)), exactAddress);
        EXPECT_EQ(Kotlin_ArcRecycledContainerCountForTests(state), 0u);
        EXPECT_EQ(Kotlin_ArcAllocatedContainerCountForTests(), physicalBefore + 2);
    });
}

TEST(ArcRecyclingTest, FifthDistinctReleaseEvictsOnlyTheLeastRecentlyUsedEntry) {
    kotlin::RunInNewThread([](MemoryState* state) {
        const int physicalBefore = Kotlin_ArcAllocatedContainerCountForTests();
        constexpr std::array<int32_t, 5> kSizes{11, 37, 79, 149, 251};
        std::array<ObjHolder, kSizes.size()> originals;
        std::array<uintptr_t, kSizes.size()> originalAddresses{};
        std::array<size_t, kSizes.size()> allocationSizes{};
        for (size_t index = 0; index < kSizes.size(); ++index) {
            ObjHeader* object = allocateRecycledByteArray(kSizes[index], originals[index]);
            originalAddresses[index] = reinterpret_cast<uintptr_t>(object);
            allocationSizes[index] = containerFor(object)->containerSize();
        }
        for (size_t index = 0; index < 4; ++index) originals[index].clear();
        ASSERT_EQ(Kotlin_ArcRecycledContainerCountForTests(state), 4u);
        EXPECT_EQ(Kotlin_ArcAllocatedContainerCountForTests(), physicalBefore + 5);
        originals[4].clear();
        ASSERT_EQ(Kotlin_ArcRecycledContainerCountForTests(state), 4u);
        EXPECT_EQ(Kotlin_ArcAllocatedContainerCountForTests(), physicalBefore + 4);
        for (size_t index = 0; index < 4; ++index) {
            EXPECT_EQ(Kotlin_ArcRecycledContainerSizeForTests(state, index), allocationSizes[4 - index]);
        }

        // Releasing index 4 fills the fifth position and evicts index 0. The four newer exact
        // sizes must remain independently reusable; no miss may flush the rest of the queue.
        std::array<ObjHolder, 4> replacements;
        for (size_t index = 1; index < kSizes.size(); ++index) {
            EXPECT_EQ(
                    reinterpret_cast<uintptr_t>(allocateRecycledByteArray(kSizes[index], replacements[index - 1])),
                    originalAddresses[index]);
        }
    });
}

TEST(ArcRecyclingTest, LargeEncodedSizeWraparoundBypassesTheBoundedCache) {
    const int physicalBefore = Kotlin_ArcAllocatedContainerCountForTests();
    kotlin::RunInNewThread([](MemoryState* state) {
        constexpr int32_t kLargeArraySize = (1 << 25) + 257;
        const int threadPhysicalBefore = Kotlin_ArcAllocatedContainerCountForTests();
        ObjHolder large;
        allocateRecycledByteArray(kLargeArraySize, large);
        EXPECT_EQ(Kotlin_ArcAllocatedContainerCountForTests(), threadPhysicalBefore + 1);
        large.clear();
        EXPECT_EQ(Kotlin_ArcRecycledContainerCountForTests(state), 0u);
        EXPECT_EQ(Kotlin_ArcAllocatedContainerCountForTests(), threadPhysicalBefore);
    });
    EXPECT_EQ(Kotlin_ArcAllocatedContainerCountForTests(), physicalBefore);
}

TEST(ArcRecyclingTest, ThreadTeardownFlushesEveryCachedPhysicalContainer) {
    const int physicalBefore = Kotlin_ArcAllocatedContainerCountForTests();
    kotlin::RunInNewThread([](MemoryState* state) {
        constexpr std::array<int32_t, 4> kSizes{23, 61, 127, 239};
        std::array<ObjHolder, kSizes.size()> objects;
        for (size_t index = 0; index < kSizes.size(); ++index) {
            allocateRecycledByteArray(kSizes[index], objects[index]);
        }
        for (auto& object : objects) object.clear();
        EXPECT_EQ(Kotlin_ArcRecycledContainerCountForTests(state), kSizes.size());
    });
    EXPECT_EQ(Kotlin_ArcAllocatedContainerCountForTests(), physicalBefore);
}

TEST(ArcForeignReferenceTest, FinalReleaseOnInitiallyUnregisteredThreadRegistersRuntimeBeforeFinalization) {
    ScopedNodeFinalizerHook finalizers;
    kotlin::RunInNewThread([] {
        ObjHolder object;
        ObjHeader* allocatedObject = AllocInstance(nodeTypeInfo.typeInfo(), object.slot());
        KRefSharedHolder holder;
        holder.init(allocatedObject);
        object.clear();

        std::atomic<bool> initiallyRegistered = true;
        std::atomic<bool> registeredAfterDispose = false;
        std::thread foreignThread([&] {
            initiallyRegistered.store(kotlin::mm::IsCurrentThreadRegistered(), std::memory_order_relaxed);
            holder.dispose();
            registeredAfterDispose.store(kotlin::mm::IsCurrentThreadRegistered(), std::memory_order_relaxed);
        });
        foreignThread.join();

        EXPECT_FALSE(initiallyRegistered.load(std::memory_order_relaxed));
        EXPECT_TRUE(registeredAfterDispose.load(std::memory_order_relaxed));
        EXPECT_TRUE(finalizerSawRegisteredRuntime.load(std::memory_order_relaxed));
        EXPECT_EQ(finalizedNodes.load(std::memory_order_relaxed), 1);
    });
}

TEST(ArcRecyclingTest, CrossThreadFinalReleaseRecyclesOnTheReleasingThread) {
    ScopedNodeFinalizerHook finalizers;
    kotlin::RunInNewThread([] {
        ObjHolder object;
        ObjHeader* allocatedObject = AllocInstance(nodeTypeInfo.typeInfo(), object.slot());
        uintptr_t originalAddress = reinterpret_cast<uintptr_t>(allocatedObject);
        KRefSharedHolder holder;
        holder.init(allocatedObject);
        object.clear();

        std::atomic<bool> initiallyRegistered = true;
        std::atomic<bool> registeredAfterDispose = false;
        std::atomic<uintptr_t> recycledAddress = 0;
        std::thread foreignThread([&] {
            initiallyRegistered.store(kotlin::mm::IsCurrentThreadRegistered(), std::memory_order_relaxed);
            holder.dispose();
            registeredAfterDispose.store(kotlin::mm::IsCurrentThreadRegistered(), std::memory_order_relaxed);

            ObjHolder replacement;
            ObjHeader* replacementObject = AllocInstance(nodeTypeInfo.typeInfo(), replacement.slot());
            recycledAddress.store(reinterpret_cast<uintptr_t>(replacementObject), std::memory_order_relaxed);
            replacement.clear();
        });
        foreignThread.join();

        EXPECT_FALSE(initiallyRegistered.load(std::memory_order_relaxed));
        EXPECT_TRUE(registeredAfterDispose.load(std::memory_order_relaxed));
        EXPECT_EQ(recycledAddress.load(std::memory_order_relaxed), originalAddress);
        EXPECT_EQ(finalizedNodes.load(std::memory_order_relaxed), 2);
    });
}

TEST(ArcRecyclingTest, FourCrossThreadFinalReleasesPopulateOnlyTheReleasingThreadCache) {
    kotlin::RunInNewThread([] {
        constexpr std::array<int32_t, 4> kSizes{13, 47, 101, 223};
        constexpr std::array<size_t, 4> kOrder{2, 0, 3, 1};
        std::array<ObjHolder, kSizes.size()> originals;
        std::array<KRefSharedHolder, kSizes.size()> shared{};
        std::array<uintptr_t, kSizes.size()> originalAddresses{};
        for (size_t index = 0; index < kSizes.size(); ++index) {
            ObjHeader* object = allocateRecycledByteArray(kSizes[index], originals[index]);
            originalAddresses[index] = reinterpret_cast<uintptr_t>(object);
            shared[index].init(object);
        }
        for (auto& original : originals) original.clear();

        std::atomic<bool> initiallyRegistered = true;
        std::atomic<bool> registeredAfterDispose = false;
        std::array<uintptr_t, kSizes.size()> replacementAddresses{};
        std::thread foreignThread([&] {
            initiallyRegistered.store(kotlin::mm::IsCurrentThreadRegistered(), std::memory_order_relaxed);
            for (auto& holder : shared) holder.dispose();
            registeredAfterDispose.store(kotlin::mm::IsCurrentThreadRegistered(), std::memory_order_relaxed);

            std::array<ObjHolder, kSizes.size()> replacements;
            for (size_t position = 0; position < kOrder.size(); ++position) {
                const size_t index = kOrder[position];
                replacementAddresses[index] = reinterpret_cast<uintptr_t>(
                        allocateRecycledByteArray(kSizes[index], replacements[position]));
            }
        });
        foreignThread.join();

        EXPECT_FALSE(initiallyRegistered.load(std::memory_order_relaxed));
        EXPECT_TRUE(registeredAfterDispose.load(std::memory_order_relaxed));
        EXPECT_EQ(replacementAddresses, originalAddresses);
    });
}

TEST(ArcRecyclingTest, ThreadLocalFiveSizeChurnRemainsZeroedAndTeardownSafe) {
    constexpr int kThreads = 8;
    constexpr int kIterations = 500;
    constexpr std::array<int32_t, 5> kSizes{17, 43, 89, 167, 263};
    std::atomic<int> stalePayloads = 0;
    std::vector<std::thread> workers;
    for (int thread = 0; thread < kThreads; ++thread) {
        workers.emplace_back([&, thread] {
            kotlin::RunInNewThread([&, thread] {
                for (int iteration = 0; iteration < kIterations; ++iteration) {
                    std::array<ObjHolder, kSizes.size()> objects;
                    for (size_t index = 0; index < kSizes.size(); ++index) {
                        ObjHeader* object = allocateRecycledByteArray(kSizes[index], objects[index]);
                        auto* first = AddressOfElementAt<uint8_t>(object->array(), 0);
                        auto* last = AddressOfElementAt<uint8_t>(object->array(), kSizes[index] - 1);
                        if (*first != 0 || *last != 0) stalePayloads.fetch_add(1, std::memory_order_relaxed);
                        *first = static_cast<uint8_t>(thread + 1);
                        *last = static_cast<uint8_t>((iteration & 0xff) + 1);
                    }
                    for (auto& object : objects) object.clear();
                }
            });
        });
    }
    for (auto& worker : workers) worker.join();
    EXPECT_EQ(stalePayloads.load(std::memory_order_relaxed), 0);
}

TEST(ArcMemoryModelTest, OrdinaryHeapObjectsAreShareableForCleanerEligibility) {
    kotlin::RunInNewThread([] {
        ObjHolder object;
        ObjHeader* allocatedObject = AllocInstance(permanentTypeInfo.typeInfo(), object.slot());
        EXPECT_TRUE(Kotlin_Any_isShareable(allocatedObject));
    });
}

TEST(ArcDestructionTest, WeakTargetIsZeroBeforeFinalizerAndCannotResurrect) {
    ScopedNodeFinalizerHook finalizers;
    kotlin::RunInNewThread([] {
        ObjHolder object;
        ObjHolder counter;
        ObjHeader* allocatedObject = AllocInstance(nodeTypeInfo.typeInfo(), object.slot());
        ObjHeader* allocatedCounter = AllocInstance(weakCounterTypeInfo.typeInfo(), counter.slot());
        installWeakCounter(allocatedObject, allocatedCounter);
        weakCounterForFinalizer.store(allocatedCounter, std::memory_order_relaxed);

        object.clear();

        EXPECT_EQ(finalizedNodes.load(std::memory_order_relaxed), 1);
        EXPECT_TRUE(resurrectionRejected.load(std::memory_order_relaxed));
        EXPECT_TRUE(weakWasZeroBeforeFinalizer.load(std::memory_order_relaxed));
        EXPECT_EQ(WeakCounter::FromObjHeader(allocatedCounter)->referred, nullptr);
    });
}

TEST(ArcWeakLockTest, ConcurrentPromotionsKeepALiveTargetVisible) {
    ScopedNodeFinalizerHook finalizers;
    kotlin::RunInNewThread([] {
        constexpr int kThreads = 8;
        constexpr int kPromotionsPerThread = 2'000;
        ObjHolder object;
        ObjHolder counter;
        ObjHeader* allocatedObject = AllocInstance(nodeTypeInfo.typeInfo(), object.slot());
        ObjHeader* allocatedCounter = AllocInstance(weakCounterTypeInfo.typeInfo(), counter.slot());
        installWeakCounter(allocatedObject, allocatedCounter);

        std::atomic<bool> start = false;
        std::atomic<int> successfulPromotions = 0;
        std::atomic<int> failedPromotions = 0;
        std::vector<std::thread> workers;
        for (int worker = 0; worker < kThreads; ++worker) {
            workers.emplace_back([&] {
                kotlin::RunInNewThread([&] {
                    while (!start.load(std::memory_order_acquire)) {
                    }
                    for (int promotion = 0; promotion < kPromotionsPerThread; ++promotion) {
                        ObjHeader* promoted = nullptr;
                        Konan_WeakReferenceCounterLegacyMM_get(allocatedCounter, &promoted);
                        if (promoted == nullptr) {
                            failedPromotions.fetch_add(1, std::memory_order_relaxed);
                        } else {
                            successfulPromotions.fetch_add(1, std::memory_order_relaxed);
                            ReleaseHeapRef(promoted);
                        }
                    }
                });
            });
        }

        start.store(true, std::memory_order_release);
        for (auto& worker : workers) worker.join();

        EXPECT_EQ(failedPromotions.load(std::memory_order_relaxed), 0);
        EXPECT_EQ(successfulPromotions.load(std::memory_order_relaxed), kThreads * kPromotionsPerThread);
        object.clear();
        EXPECT_EQ(finalizedNodes.load(std::memory_order_relaxed), 1);
    });
}

TEST(ArcRecyclingTest, RecycledAddressDoesNotResurrectClearedWeakReference) {
    ScopedNodeFinalizerHook finalizers;
    kotlin::RunInNewThread([] {
        ObjHolder object;
        ObjHolder counter;
        ObjHeader* allocatedObject = AllocInstance(nodeTypeInfo.typeInfo(), object.slot());
        uintptr_t originalAddress = reinterpret_cast<uintptr_t>(allocatedObject);
        ObjHeader* allocatedCounter = AllocInstance(weakCounterTypeInfo.typeInfo(), counter.slot());
        installWeakCounter(allocatedObject, allocatedCounter);

        object.clear();
        EXPECT_EQ(finalizedNodes.load(std::memory_order_relaxed), 1);

        ObjHolder replacement;
        ObjHeader* replacementObject = AllocInstance(nodeTypeInfo.typeInfo(), replacement.slot());
        EXPECT_EQ(reinterpret_cast<uintptr_t>(replacementObject), originalAddress);

        ObjHeader* promoted = nullptr;
        Konan_WeakReferenceCounterLegacyMM_get(allocatedCounter, &promoted);
        EXPECT_EQ(promoted, nullptr);

        replacement.clear();
        EXPECT_EQ(finalizedNodes.load(std::memory_order_relaxed), 2);
    });
}

TEST(ArcDeinitTest, RunsInitializedHooksDerivedToBaseExactlyOnceBeforeFieldsAreReleased) {
    kotlin::RunInNewThread([] {
        arcDeinitOrder.clear();
        arcDeinitFieldWasAlive.store(false, std::memory_order_relaxed);
        arcDeinitResurrectionRejected.store(false, std::memory_order_relaxed);

        kotlin::test_support::TypeInfoHolder baseType{
                kotlin::test_support::TypeInfoHolder::ObjectBuilder<NodePayload>().setArcDestroy(baseArcDestroy)};
        kotlin::test_support::TypeInfoHolder middleType{
                kotlin::test_support::TypeInfoHolder::ObjectBuilder<NodePayload>().setSuperType(baseType.typeInfo())};
        kotlin::test_support::TypeInfoHolder derivedType{
                kotlin::test_support::TypeInfoHolder::ObjectBuilder<NodePayload>()
                        .setSuperType(middleType.typeInfo())
                        .setArcDestroy(derivedArcDestroyWithField)};

        ObjHolder object;
        ObjHolder child;
        ObjHeader* allocated = AllocInstance(derivedType.typeInfo(), object.slot());
        ObjHeader* allocatedChild = AllocInstance(nodeTypeInfo.typeInfo(), child.slot());
        UpdateHeapRef(nextSlot(allocated), allocatedChild);
        child.clear();

        Kotlin_ArcMarkDeinitInitialized(allocated, baseType.typeInfo());
        Kotlin_ArcMarkDeinitInitialized(allocated, derivedType.typeInfo());
        object.clear();

        ASSERT_EQ(arcDeinitOrder.size(), 2u);
        EXPECT_STREQ(arcDeinitOrder[0], "derived");
        EXPECT_STREQ(arcDeinitOrder[1], "base");
        EXPECT_TRUE(arcDeinitFieldWasAlive.load(std::memory_order_relaxed));
        EXPECT_TRUE(arcDeinitResurrectionRejected.load(std::memory_order_relaxed));
    });
}

TEST(ArcDeinitTest, ConstructorFailureRunsOnlyTheSuccessfullyInitializedPrefix) {
    kotlin::RunInNewThread([] {
        arcDeinitOrder.clear();
        kotlin::test_support::TypeInfoHolder baseType{
                kotlin::test_support::TypeInfoHolder::ObjectBuilder<Payload>().setArcDestroy(baseArcDestroy)};
        kotlin::test_support::TypeInfoHolder derivedType{
                kotlin::test_support::TypeInfoHolder::ObjectBuilder<Payload>()
                        .setSuperType(baseType.typeInfo())
                        .setArcDestroy(derivedArcDestroy)};

        ObjHolder object;
        ObjHeader* allocated = AllocInstance(derivedType.typeInfo(), object.slot());
        Kotlin_ArcMarkDeinitInitialized(allocated, baseType.typeInfo());
        object.clear();

        ASSERT_EQ(arcDeinitOrder.size(), 1u);
        EXPECT_STREQ(arcDeinitOrder[0], "base");
    });
}

TEST(ArcDeinitTest, UninitializedObjectDoesNotRunDestroyHooks) {
    kotlin::RunInNewThread([] {
        arcDeinitOrder.clear();
        kotlin::test_support::TypeInfoHolder type{
                kotlin::test_support::TypeInfoHolder::ObjectBuilder<Payload>().setArcDestroy(derivedArcDestroy)};

        ObjHolder object;
        AllocInstance(type.typeInfo(), object.slot());
        object.clear();

        EXPECT_TRUE(arcDeinitOrder.empty());
    });
}

TEST(ArcDeinitDeathTest, EscapingForeignExceptionTerminates) {
    EXPECT_DEATH(
            kotlin::RunInNewThread([] {
                kotlin::test_support::TypeInfoHolder type{
                        kotlin::test_support::TypeInfoHolder::ObjectBuilder<Payload>().setArcDestroy(throwingArcDestroy)};
                ObjHolder object;
                ObjHeader* allocated = AllocInstance(type.typeInfo(), object.slot());
                Kotlin_ArcMarkDeinitInitialized(allocated, type.typeInfo());
                object.clear();
            }),
            "");
}

TEST(ArcDeinitDeathTest, EscapingKotlinExceptionTerminates) {
    EXPECT_DEATH(
            kotlin::RunInNewThread([] {
                kotlin::test_support::TypeInfoHolder exceptionType{
                        kotlin::test_support::TypeInfoHolder::ObjectBuilder<Payload>().setSuperType(theThrowableTypeInfo)};
                kotlin::test_support::TypeInfoHolder deinitType{
                        kotlin::test_support::TypeInfoHolder::ObjectBuilder<Payload>().setArcDestroy(throwingKotlinArcDestroy)};
                ObjHolder exception;
                ObjHolder object;
                arcDeinitException = AllocInstance(exceptionType.typeInfo(), exception.slot());
                ObjHeader* allocated = AllocInstance(deinitType.typeInfo(), object.slot());
                Kotlin_ArcMarkDeinitInitialized(allocated, deinitType.typeInfo());
                object.clear();
            }),
            "");
}

TEST(ArcDestructionTest, WeakPromotionRacesFinalReleaseWithoutResurrection) {
    ScopedNodeFinalizerHook finalizers;
    kotlin::RunInNewThread([] {
        ObjHolder object;
        ObjHolder counter;
        ObjHeader* allocatedObject = AllocInstance(nodeTypeInfo.typeInfo(), object.slot());
        ObjHeader* allocatedCounter = AllocInstance(weakCounterTypeInfo.typeInfo(), counter.slot());
        ObjHeader* expectedChild = permanentHeader();
        UpdateHeapRef(nextSlot(allocatedObject), expectedChild);
        installWeakCounter(allocatedObject, allocatedCounter);

        std::atomic<bool> start = false;
        std::atomic<bool> stop = false;
        std::atomic<int> successfulPromotions = 0;
        std::atomic<int> invalidPromotions = 0;
        std::vector<std::thread> workers;
        for (int worker = 0; worker < 4; ++worker) {
            workers.emplace_back([&] {
                kotlin::RunInNewThread([&] {
                    while (!start.load(std::memory_order_acquire)) {
                    }
                    while (!stop.load(std::memory_order_acquire)) {
                        ObjHeader* promoted = nullptr;
                        Konan_WeakReferenceCounterLegacyMM_get(allocatedCounter, &promoted);
                        if (promoted != nullptr) {
                            if (Node::FromObjHeader(promoted)->next != expectedChild) {
                                invalidPromotions.fetch_add(1, std::memory_order_relaxed);
                            }
                            successfulPromotions.fetch_add(1, std::memory_order_relaxed);
                            ReleaseHeapRef(promoted);
                        }
                    }
                });
            });
        }

        start.store(true, std::memory_order_release);
        while (successfulPromotions.load(std::memory_order_relaxed) < 100) {
            std::this_thread::yield();
        }
        object.clear();
        stop.store(true, std::memory_order_release);
        for (auto& worker : workers) worker.join();

        ObjHeader* promoted = nullptr;
        Konan_WeakReferenceCounterLegacyMM_get(allocatedCounter, &promoted);
        EXPECT_EQ(promoted, nullptr);
        EXPECT_EQ(finalizedNodes.load(std::memory_order_relaxed), 1);
        EXPECT_EQ(invalidPromotions.load(std::memory_order_relaxed), 0);
        EXPECT_TRUE(resurrectionRejected.load(std::memory_order_relaxed));
    });
}

TEST(ArcDestructionTest, StrongCycleSurvivesUntilExplicitlyBroken) {
    ScopedNodeFinalizerHook finalizers;
    kotlin::RunInNewThread([] {
        ObjHolder first;
        ObjHolder second;
        ObjHeader* firstObject = AllocInstance(nodeTypeInfo.typeInfo(), first.slot());
        ObjHeader* secondObject = AllocInstance(nodeTypeInfo.typeInfo(), second.slot());
        UpdateHeapRef(nextSlot(firstObject), secondObject);
        UpdateHeapRef(nextSlot(secondObject), firstObject);
        first.clear();
        second.clear();

        Kotlin_native_internal_GC_collect(nullptr);
        EXPECT_EQ(finalizedNodes.load(std::memory_order_relaxed), 0);

        UpdateHeapRef(nextSlot(firstObject), nullptr);
        EXPECT_EQ(finalizedNodes.load(std::memory_order_relaxed), 2);
    });
}

TEST(ArcDestructionTest, FlaggedFieldDestroyThunkUsesCanonicalBarrierAndLegacyCallbackFallsBack) {
    ScopedNodeFinalizerHook finalizers;
    kotlin::RunInNewThread([] {
        arcFieldDestroyThunkCalls.store(0, std::memory_order_relaxed);
        forgedLegacyMarkCallbackCalls.store(0, std::memory_order_relaxed);

        kotlin::test_support::TypeInfoHolder thunkType{
                kotlin::test_support::TypeInfoHolder::ObjectBuilder<NodePayload>()
                        .addFlag(TF_HAS_ARC_DESTROY_THUNK)};
        thunkType.typeInfo()->processObjectInMark = generatedArcFieldDestroyThunk;
        {
            ObjHolder object;
            ObjHolder child;
            ObjHeader* allocated = AllocInstance(thunkType.typeInfo(), object.slot());
            ObjHeader* allocatedChild = AllocInstance(nodeTypeInfo.typeInfo(), child.slot());
            UpdateHeapRef(&Node::FromObjHeader(allocated)->next, allocatedChild);
            child.clear();
            object.clear();
        }
        EXPECT_EQ(arcFieldDestroyThunkCalls.load(std::memory_order_relaxed), 1);
        EXPECT_EQ(finalizedNodes.load(std::memory_order_relaxed), 1);

        // A class-specific callback from an old KLIB is not a destroy thunk without the new flag.
        // The metadata fallback must release its field and must never invoke the forged callback.
        kotlin::test_support::TypeInfoHolder legacyType{
                kotlin::test_support::TypeInfoHolder::ObjectBuilder<NodePayload>()};
        legacyType.typeInfo()->processObjectInMark = forgedLegacyMarkCallback;
        {
            ObjHolder object;
            ObjHolder child;
            ObjHeader* allocated = AllocInstance(legacyType.typeInfo(), object.slot());
            ObjHeader* allocatedChild = AllocInstance(nodeTypeInfo.typeInfo(), child.slot());
            UpdateHeapRef(&Node::FromObjHeader(allocated)->next, allocatedChild);
            child.clear();
            object.clear();
        }
        EXPECT_EQ(forgedLegacyMarkCallbackCalls.load(std::memory_order_relaxed), 0);
        EXPECT_EQ(finalizedNodes.load(std::memory_order_relaxed), 2);
    });
}

TEST(ArcDestructionTest, FieldDestroyThunkReleasesInitializedFieldsAfterConstructorFailure) {
    ScopedNodeFinalizerHook finalizers;
    kotlin::RunInNewThread([] {
        arcDeinitOrder.clear();
        arcFieldDestroyThunkCalls.store(0, std::memory_order_relaxed);

        kotlin::test_support::TypeInfoHolder baseType{
                kotlin::test_support::TypeInfoHolder::ObjectBuilder<Payload>().setArcDestroy(baseArcDestroy)};
        kotlin::test_support::TypeInfoHolder derivedType{
                kotlin::test_support::TypeInfoHolder::ObjectBuilder<NodePayload>()
                        .addFlag(TF_HAS_ARC_DESTROY_THUNK)
                        .setSuperType(baseType.typeInfo())
                        .setArcDestroy(derivedArcDestroy)};
        derivedType.typeInfo()->processObjectInMark = generatedArcFieldDestroyThunk;

        ObjHolder object;
        ObjHolder child;
        ObjHeader* allocated = AllocInstance(derivedType.typeInfo(), object.slot());
        ObjHeader* allocatedChild = AllocInstance(nodeTypeInfo.typeInfo(), child.slot());
        UpdateHeapRef(&Node::FromObjHeader(allocated)->next, allocatedChild);
        child.clear();

        // Model a derived-constructor failure after successful base delegation and one field store.
        Kotlin_ArcMarkDeinitInitialized(allocated, baseType.typeInfo());
        object.clear();

        ASSERT_EQ(arcDeinitOrder.size(), 1u);
        EXPECT_STREQ(arcDeinitOrder[0], "base");
        EXPECT_EQ(arcFieldDestroyThunkCalls.load(std::memory_order_relaxed), 1);
        EXPECT_EQ(finalizedNodes.load(std::memory_order_relaxed), 1);
    });
}

TEST(ArcDestructionTest, DeepGraphUsesIterativeDestructionWorklist) {
    constexpr int kDepth = 20'000;
    ScopedNodeFinalizerHook finalizers;
    kotlin::RunInNewThread([=] {
        ObjHolder root;
        ObjHeader* current = AllocInstance(nodeTypeInfo.typeInfo(), root.slot());
        for (int index = 1; index < kDepth; ++index) {
            ObjHolder next;
            ObjHeader* nextObject = AllocInstance(nodeTypeInfo.typeInfo(), next.slot());
            UpdateHeapRef(nextSlot(current), nextObject);
            current = nextObject;
        }

        root.clear();
        EXPECT_EQ(finalizedNodes.load(std::memory_order_relaxed), kDepth);
    });
}

TEST(ArcDestructionTest, PermanentObjectsRemainImmortal) {
    ScopedNodeFinalizerHook finalizers;
    kotlin::RunInNewThread([] {
        Node permanent(nodeTypeInfo.typeInfo());
        permanent.header()->typeInfoOrMeta_ =
                setPointerBits(permanent.header()->typeInfoOrMeta_, OBJECT_TAG_PERMANENT_CONTAINER);
        ObjHolder holder(permanent.header());
        holder.clear();
        EXPECT_EQ(finalizedNodes.load(std::memory_order_relaxed), 0);
    });
}

TEST(ArcDestructionTest, CollectorControlsAreNoOpsAndCycleCollectionIsUnsupported) {
    kotlin::RunInNewThread([] {
        Kotlin_native_internal_GC_collect(nullptr);
        Kotlin_native_internal_GC_suspend(nullptr);
        Kotlin_native_internal_GC_resume(nullptr);
        Kotlin_native_internal_GC_stop(nullptr);
        Kotlin_native_internal_GC_start(nullptr);
        Kotlin_native_internal_GC_setThreshold(nullptr, 1);
        Kotlin_native_internal_GC_setCollectCyclesThreshold(nullptr, 1);
        Kotlin_native_internal_GC_setThresholdAllocations(nullptr, 1);
        Kotlin_native_internal_GC_setTuneThreshold(nullptr, true);
        EXPECT_EQ(Kotlin_native_internal_GC_getThreshold(nullptr), -1);
        EXPECT_EQ(Kotlin_native_internal_GC_getCollectCyclesThreshold(nullptr), -1);
        EXPECT_EQ(Kotlin_native_internal_GC_getThresholdAllocations(nullptr), -1);
        EXPECT_FALSE(Kotlin_native_internal_GC_getTuneThreshold(nullptr));
        EXPECT_THROW(Kotlin_native_internal_GC_collectCyclic(nullptr), std::runtime_error);
    });
}
