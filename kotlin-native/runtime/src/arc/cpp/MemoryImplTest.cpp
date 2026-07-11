/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

#include "Memory.h"

#include <atomic>
#include <thread>
#include <vector>

#include "Exceptions.h"
#include "FinalizerHooks.hpp"
#include "gtest/gtest.h"
#include "MemorySharedRefs.hpp"
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

TEST(ArcReferenceCountStateTest, FinalReleaseAtomicallyEntersPermanentDeallocatingState) {
    ContainerHeader header{};
    header.setRefCount(1);
    EXPECT_EQ(header.decRefCount<true>(), 0);
    EXPECT_TRUE(header.arcDeallocating());
    EXPECT_FALSE(header.tryIncRefCount<true>());
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

using Object = kotlin::test_support::Object<Payload>;
using Node = kotlin::test_support::Object<NodePayload>;
using WeakCounter = kotlin::test_support::Object<WeakCounterPayload>;

struct FrameStorage {
    FrameOverlay overlay{};
    ObjHeader* parameter = nullptr;
    ObjHeader* local = nullptr;

    ObjHeader** start() { return reinterpret_cast<ObjHeader**>(&overlay); }
    static constexpr int kParameters = 1;
};

static_assert(sizeof(FrameStorage) % sizeof(void*) == 0);
constexpr int kFrameStorageCount = sizeof(FrameStorage) / sizeof(void*);

kotlin::test_support::TypeInfoHolder permanentTypeInfo{
        kotlin::test_support::TypeInfoHolder::ObjectBuilder<Payload>()};
Object permanentObject(permanentTypeInfo.typeInfo());
kotlin::test_support::TypeInfoHolder nodeTypeInfo{
        kotlin::test_support::TypeInfoHolder::ObjectBuilder<NodePayload>().addFlag(TF_HAS_FINALIZER)};
kotlin::test_support::TypeInfoHolder weakCounterTypeInfo{
        kotlin::test_support::TypeInfoHolder::ObjectBuilder<WeakCounterPayload>()};
std::atomic<int> finalizedNodes = 0;
std::atomic<bool> finalizerSawRegisteredRuntime = false;
std::atomic<FrameOverlay*> finalizerObservedFrame = nullptr;
std::atomic<bool> resurrectionRejected = false;
std::atomic<bool> weakWasZeroBeforeFinalizer = false;
std::atomic<ObjHeader*> weakCounterForFinalizer = nullptr;
std::vector<const char*> arcDeinitOrder;
std::atomic<bool> arcDeinitFieldWasAlive = false;
std::atomic<bool> arcDeinitResurrectionRejected = false;
ObjHeader* arcDeinitException = nullptr;

extern "C" OBJ_GETTER(Konan_WeakReferenceCounterLegacyMM_get, ObjHeader* counter);

ObjHeader* permanentHeader() {
    permanentObject.header()->typeInfoOrMeta_ =
            setPointerBits(permanentObject.header()->typeInfoOrMeta_, OBJECT_TAG_PERMANENT_CONTAINER);
    return permanentObject.header();
}

void countNodeFinalizer(ObjHeader* object) {
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

void baseArcDestroy(ObjHeader* object) {
    arcDeinitOrder.push_back("base");
    arcDeinitResurrectionRejected.store(!TryAddHeapRef(object), std::memory_order_relaxed);
}

void derivedArcDestroy(ObjHeader* object) {
    arcDeinitOrder.push_back("derived");
    arcDeinitResurrectionRejected.store(!TryAddHeapRef(object), std::memory_order_relaxed);
}

void derivedArcDestroyWithField(ObjHeader* object) {
    derivedArcDestroy(object);
    arcDeinitFieldWasAlive.store(Node::FromObjHeader(object)->next != nullptr, std::memory_order_relaxed);
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
        installWeakCounter(allocatedObject, allocatedCounter);

        std::atomic<bool> start = false;
        std::atomic<bool> stop = false;
        std::atomic<int> successfulPromotions = 0;
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
