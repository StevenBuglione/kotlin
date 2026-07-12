/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

package org.jetbrains.kotlin.backend.konan.llvm

import llvm.*
import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.util.ir2string
import org.jetbrains.kotlin.ir.util.render
import org.jetbrains.kotlin.name.Name

internal fun IrElement.needDebugInfo(context: Context) = context.shouldContainDebugInfo() || (this is IrVariable && this.isVar)

internal class VariableManager(val functionGenerationContext: FunctionGenerationContext) {
    internal interface Record {
        fun load(resultSlot: LLVMValueRef?) : LLVMValueRef
        fun store(value: LLVMValueRef)
        fun address() : LLVMValueRef
    }

    inner class SlotRecord(val address: LLVMValueRef, val refSlot: Boolean, val isVar: Boolean) : Record {
        override fun load(resultSlot: LLVMValueRef?) : LLVMValueRef = functionGenerationContext.loadSlot(address, isVar, resultSlot)
        override fun store(value: LLVMValueRef) {
            functionGenerationContext.storeAny(value, address, true)
        }
        override fun address() : LLVMValueRef = this.address
        override fun toString() = (if (refSlot) "refslot" else "slot") + " for ${address}"
    }

    inner class ParameterRecord(val address: LLVMValueRef, val refSlot: Boolean) : Record {
        override fun load(resultSlot: LLVMValueRef?): LLVMValueRef = functionGenerationContext.loadSlot(address, false, resultSlot)
        override fun store(value: LLVMValueRef) = functionGenerationContext.store(value, address)
        override fun address() : LLVMValueRef = this.address
        override fun toString() = (if (refSlot) "refslot" else "slot") + " for ${address}"
    }

    class ValueRecord(val value: LLVMValueRef, val name: Name) : Record {
        override fun load(resultSlot: LLVMValueRef?) : LLVMValueRef = value
        override fun store(value: LLVMValueRef) = throw Error("writing to immutable: ${name}")
        override fun address() : LLVMValueRef = throw Error("no address for: ${name}")
        override fun toString() = "value of ${value} from ${name}"
    }

    inner class NonOwningReferenceRecord(val rawAddress: LLVMValueRef, val name: Name) : Record {
        override fun load(resultSlot: LLVMValueRef?): LLVMValueRef =
            error("generic load of non-owning rooted projection cursor: $name")

        override fun store(value: LLVMValueRef) =
            error("generic store of non-owning rooted projection cursor: $name")

        override fun address(): LLVMValueRef =
            error("address escape of non-owning rooted projection cursor: $name")

        fun loadNonOwning(): LLVMValueRef = functionGenerationContext.loadSlot(rawAddress, false, null)

        fun storeNonOwning(value: LLVMValueRef) {
            require(functionGenerationContext.isObjectRef(value)) {
                "non-owning rooted projection cursor requires an object reference"
            }
            functionGenerationContext.store(value, rawAddress)
        }

        override fun toString() = "non-owning rooted projection cursor $name"
    }

    val variables: ArrayList<Record> = arrayListOf()
    val contextVariablesToIndex: HashMap<IrValueDeclaration, Int> = hashMapOf()

    // Clears inner state of variable manager.
    fun clear() {
        skipSlots = 0
        variables.clear()
        contextVariablesToIndex.clear()
    }

    fun createVariable(valueDeclaration: IrValueDeclaration, value: LLVMValueRef? = null, variableLocation: VariableDebugLocation?) : Int {
        val isVar = valueDeclaration is IrVariable && valueDeclaration.isVar
        // Note that we always create slot for object references for memory management.
        if (!functionGenerationContext.context.shouldContainDebugInfo() && !isVar && value != null)
            return createImmutable(valueDeclaration, value)
        else
            // Unfortunately, we have to create mutable slots here,
            // as even vals can be assigned on multiple paths. However, we use varness
            // knowledge, as anonymous slots are created only for true vars (for vals
            // their single assigner already have slot).
            return createMutable(valueDeclaration, isVar, value, variableLocation)
    }

    internal fun createMutable(valueDeclaration: IrValueDeclaration,
                               isVar: Boolean, value: LLVMValueRef? = null, variableLocation: VariableDebugLocation?) : Int {
        assert(!contextVariablesToIndex.contains(valueDeclaration)) {
            "Could not find ${valueDeclaration.render()} in contextVariablesToIndex"
        }
        val index = variables.size
        val type = valueDeclaration.type.toLLVMType(functionGenerationContext.llvm)
        val slot = functionGenerationContext.alloca(type, valueDeclaration.name.asString(), variableLocation)
        if (value != null)
            functionGenerationContext.storeAny(value, slot, true)
        variables.add(SlotRecord(slot, functionGenerationContext.isObjectType(type), isVar))
        contextVariablesToIndex[valueDeclaration] = index
        return index
    }

    internal var skipSlots = 0
    internal fun createParameterOnStack(valueDeclaration: IrValueDeclaration, variableLocation: VariableDebugLocation?): Int {
        assert(!contextVariablesToIndex.contains(valueDeclaration))
        val index = variables.size
        val type = valueDeclaration.type.toLLVMType(functionGenerationContext.llvm)
        val slot = functionGenerationContext.alloca(
                type, "p-${valueDeclaration.name.asString()}", variableLocation)
        val isObject = functionGenerationContext.isObjectType(type)
        variables.add(ParameterRecord(slot, isObject))
        contextVariablesToIndex[valueDeclaration] = index
        if (isObject)
            skipSlots++
        return index
    }

    internal fun createParameter(valueDeclaration: IrValueDeclaration, value: LLVMValueRef) =
            createImmutable(valueDeclaration, value)

    // Creates anonymous mutable variable.
    // Think of slot reuse.
    fun createAnonymousSlot(value: LLVMValueRef? = null) : LLVMValueRef {
        val index = createAnonymousMutable(functionGenerationContext.kObjHeaderPtr, value)
        return addressOf(index)
    }

    private fun createAnonymousMutable(type: LLVMTypeRef, value: LLVMValueRef? = null) : Int {
        val index = variables.size
        val slot = functionGenerationContext.alloca(type, variableLocation = null)
        if (value != null)
            functionGenerationContext.storeAny(value, slot, true)
        variables.add(SlotRecord(slot, functionGenerationContext.isObjectType(type), true))
        return index
    }

    internal fun createImmutable(valueDeclaration: IrValueDeclaration, value: LLVMValueRef) : Int {
        if (contextVariablesToIndex.containsKey(valueDeclaration))
            throw Error("${ir2string(valueDeclaration)} is already defined")
        val index = variables.size
        variables.add(ValueRecord(value, valueDeclaration.name))
        contextVariablesToIndex[valueDeclaration] = index
        return index
    }

    internal fun createNonOwningReference(valueDeclaration: IrVariable, value: LLVMValueRef): Int {
        require(valueDeclaration.isVar && functionGenerationContext.isObjectRef(value)) {
            "non-owning rooted projection cursor requires a mutable reference variable: ${valueDeclaration.render()}"
        }
        require(!contextVariablesToIndex.containsKey(valueDeclaration)) {
            "${valueDeclaration.render()} is already defined"
        }
        val index = variables.size
        val address = functionGenerationContext.allocaNonOwningReference(valueDeclaration.name.asString())
        val record = NonOwningReferenceRecord(address, valueDeclaration.name)
        record.storeNonOwning(value)
        variables.add(record)
        contextVariablesToIndex[valueDeclaration] = index
        return index
    }

    fun indexOf(valueDeclaration: IrValueDeclaration) : Int {
        return contextVariablesToIndex.getOrElse(valueDeclaration) { -1 }
    }

    fun addressOf(index: Int): LLVMValueRef {
        return variables[index].address()
    }

    fun load(index: Int, resultSlot: LLVMValueRef?): LLVMValueRef {
        return variables[index].load(resultSlot)
    }

    /**
     * Loads a mutable reference without creating an anonymous owning root. Callers must have a
     * verified use interval contained by the lifetime of this stack slot.
     */
    fun loadBorrowedMutableReference(index: Int): LLVMValueRef {
        val record = variables[index]
        require(record is SlotRecord && record.isVar && record.refSlot) {
            "Borrowed mutable load requires a mutable reference slot, got $record"
        }
        return functionGenerationContext.loadSlot(record.address, false, null)
    }

    fun store(value: LLVMValueRef, index: Int) {
        variables[index].store(value)
    }

    /**
     * Performs the exact retain-before-release stack replacement authorized for a borrowed strong
     * field projection. Keeping this separate from generic [store] makes both the mutable/reference
     * slot precondition and the ownership-sensitive codegen path explicit.
     */
    fun storeBorrowedStrongProjection(value: LLVMValueRef, index: Int) {
        val record = variables[index]
        require(record is SlotRecord && record.isVar && record.refSlot) {
            "Borrowed strong projection replacement requires a mutable reference slot, got $record"
        }
        functionGenerationContext.storeStackRef(value, record.address)
    }

    fun loadRootedProjection(index: Int): LLVMValueRef {
        val record = variables[index]
        require(record is NonOwningReferenceRecord) {
            "rooted projection load requires a non-owning cursor record, got $record"
        }
        return record.loadNonOwning()
    }

    fun storeRootedProjection(value: LLVMValueRef, index: Int) {
        val record = variables[index]
        require(record is NonOwningReferenceRecord) {
            "rooted projection advance requires a non-owning cursor record, got $record"
        }
        record.storeNonOwning(value)
    }
}

internal data class VariableDebugLocation(val localVariable: DILocalVariableRef, val location:DILocationRef?, val file:DIFileRef, val line:Int)

internal fun debugInfoLocalVariableLocation(builder: DIBuilderRef?,
        functionScope: DIScopeOpaqueRef, diType: DITypeOpaqueRef, name:Name, file: DIFileRef, line: Int,
        location: DILocationRef?): VariableDebugLocation {
    val variableDeclaration = DICreateAutoVariable(
            builder = builder,
            scope = functionScope,
            name = name.asString(),
            file = file,
            line = line,
            type = diType)

    return VariableDebugLocation(localVariable = variableDeclaration!!, location = location, file = file, line = line)
}

internal fun debugInfoParameterLocation(builder: DIBuilderRef?,
                                        functionScope: DIScopeOpaqueRef, diType: DITypeOpaqueRef,
                                        name:Name, argNo: Int, file: DIFileRef, line: Int,
                                        location: DILocationRef?): VariableDebugLocation {
    val variableDeclaration = DICreateParameterVariable(
            builder = builder,
            scope = functionScope,
            name = name.asString(),
            argNo = argNo,
            file = file,
            line = line,
            type = diType)

    return VariableDebugLocation(localVariable = variableDeclaration!!, location = location, file = file, line = line)
}
