import kotlin.native.Retain

private class SemanticBranchPayload(val value: Int)

// OPT-LABEL: define internal zeroext i1 @"kfun:semanticBranchPhi#internal"
// OPT: %selected = alloca %struct.ObjHeader*
// OPT-NOT: call void @UpdateStackRef
// OPT: store %struct.ObjHeader* null, %struct.ObjHeader** %selected
// OPT: store %struct.ObjHeader* %1, %struct.ObjHeader** %selected
// OPT: store %struct.ObjHeader* %2, %struct.ObjHeader** %selected
// OPT: load %struct.ObjHeader*, %struct.ObjHeader** %selected
// OPT-NOT: call void @UpdateStackRef
// OPT: ret i1
@Retain
private fun semanticBranchPhi(
    flag: Boolean,
    left: SemanticBranchPayload,
    right: SemanticBranchPayload,
): Boolean {
    var selected: SemanticBranchPayload? = null
    if (flag) selected = left else selected = right
    return selected === left
}

// FINAL-LABEL: define internal zeroext i1 @"kfun:semanticBranchPhi#internal"
// FINAL-NOT: %selected = alloca
// FINAL-NOT: @UpdateStackRef
// FINAL: [[SELECTED:%[A-Za-z0-9_.]+]] = select i1 %0, %struct.ObjHeader* %1, %struct.ObjHeader* %2
// FINAL: icmp eq %struct.ObjHeader* [[SELECTED]], %1
// FINAL: ret i1

// DIAGNOSTICS-LABEL: define internal zeroext i1 @"kfun:semanticBranchPhi#internal"
// DIAGNOSTICS: %selected = getelementptr %struct.ObjHeader*
// DIAGNOSTICS: call void @UpdateStackRef(%struct.ObjHeader** %selected,
// DIAGNOSTICS: ret i1

// DEBUG-LABEL: define internal zeroext i1 @"kfun:semanticBranchPhi#internal"
// DEBUG: %selected = getelementptr %struct.ObjHeader*
// DEBUG: call void @UpdateStackRef(%struct.ObjHeader** %selected,
// DEBUG: ret i1

// STRICT-LABEL: define internal zeroext i1 @"kfun:semanticBranchPhi#internal"
// STRICT: %selected = getelementptr %struct.ObjHeader*
// STRICT: store %struct.ObjHeader*
// STRICT: ret i1

// This almost-identical body must remain on ordinary owning-slot codegen: the selected value has
// an additional post-merge use, so the first exact SemanticARC web is not authorized.
// OPT-LABEL: define internal zeroext i1 @"kfun:semanticBranchPhiExtraUse#internal"
// OPT: call void @UpdateStackRef
@Retain
private fun semanticBranchPhiExtraUse(
    flag: Boolean,
    left: SemanticBranchPayload,
    right: SemanticBranchPayload,
): Boolean {
    var selected: SemanticBranchPayload? = null
    if (flag) selected = left else selected = right
    val observed = selected === left
    return observed && selected === left
}

fun main() {
    val left = SemanticBranchPayload(1)
    val right = SemanticBranchPayload(2)
    var checksum = 0
    repeat(100_000) { index ->
        if (semanticBranchPhi((index and 1) == 0, left, right)) checksum++
    }
    check(checksum == 50_000)
    check(semanticBranchPhiExtraUse(true, left, right))
    check(!semanticBranchPhiExtraUse(false, left, right))
    println("ARC_SEMANTIC_BRANCH_PHI_OK:$checksum")
}
