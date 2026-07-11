/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

#include "Memory.h"

#include <atomic>

#include "FinalizerHooks.hpp"
#include "gtest/gtest.h"
#include "ObjectTestSupport.hpp"
#include "PointerBits.h"
#include "TestSupport.hpp"

#if !defined(KONAN_ARC_MEMORY_MANAGER) || KONAN_ARC_MEMORY_MANAGER != 1
#error "ARC runtime tests must use the dedicated ARC compile-time configuration"
#endif

TEST(ArcMemoryModelTest, HasDedicatedRuntimeIdentity) {
    EXPECT_EQ(CurrentMemoryModel, MemoryModel::kArc);
    EXPECT_NE(CurrentMemoryModel, MemoryModel::kStrict);
    EXPECT_NE(CurrentMemoryModel, MemoryModel::kRelaxed);
    EXPECT_NE(CurrentMemoryModel, MemoryModel::kExperimental);
}

namespace {

struct Payload {
    static constexpr std::array<ObjHeader* Payload::*, 0> kFields{};
};

struct NodePayload {
    ObjHeader* next = nullptr;
    static constexpr std::array<ObjHeader* NodePayload::*, 1> kFields{&NodePayload::next};
};

using Object = kotlin::test_support::Object<Payload>;
using Node = kotlin::test_support::Object<NodePayload>;

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
std::atomic<int> finalizedNodes = 0;

ObjHeader* permanentHeader() {
    permanentObject.header()->typeInfoOrMeta_ =
            setPointerBits(permanentObject.header()->typeInfoOrMeta_, OBJECT_TAG_PERMANENT_CONTAINER);
    return permanentObject.header();
}

void countNodeFinalizer(ObjHeader* object) {
    if (object->type_info() == nodeTypeInfo.typeInfo()) finalizedNodes.fetch_add(1, std::memory_order_relaxed);
}

class ScopedNodeFinalizerHook {
public:
    ScopedNodeFinalizerHook() {
        finalizedNodes.store(0, std::memory_order_relaxed);
        kotlin::SetFinalizerHookForTesting(countNodeFinalizer);
    }

    ~ScopedNodeFinalizerHook() { kotlin::SetFinalizerHookForTesting(nullptr); }
};

ObjHeader** nextSlot(ObjHeader* node) {
    return &Node::FromObjHeader(node)->next;
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

TEST(ArcFrameTest, RetargetDoesNotInspectInvalidatedSkippedFrameStorage) {
    kotlin::RunInNewThread([] {
        FrameStorage target;
        FrameStorage invalidated;
        target.local = permanentHeader();
        EnterFrame(target.start(), FrameStorage::kParameters, kFrameStorageCount);
        EnterFrame(invalidated.start(), FrameStorage::kParameters, kFrameStorageCount);

        invalidated.overlay.parameters = INT32_MAX;
        invalidated.overlay.count = -1;
        invalidated.local = reinterpret_cast<ObjHeader*>(0x21);
        SetCurrentFrame(target.start());

        EXPECT_EQ(getCurrentFrame(), &target.overlay);
        LeaveFrame(target.start(), FrameStorage::kParameters, kFrameStorageCount);
        EXPECT_EQ(getCurrentFrame(), nullptr);
        EXPECT_EQ(target.local, nullptr);
    });
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
