package geodes.sms.neo4j.io.controllers

import geodes.sms.neo4j.io.*
import geodes.sms.neo4j.io.entity.INodeEntity
import geodes.sms.neo4j.io.entity.IPropertyAccessor
import geodes.sms.neo4j.io.exception.LoverBoundExceedException
import geodes.sms.neo4j.io.exception.UpperBoundExceedException
import org.neo4j.driver.Value

internal class NodeController private constructor(
    private val mapper: Mapper,
    id: Long,
    override val label: String,
    propsDiff: HashMap<String, Value>,
    override val outRefCount: MutableMap<String, Int>, //rType --> count
    state: EntityState
) : INodeController, StateListener, PropertyAccessor(propsDiff) {

    override fun getState() = state.getState()
    override var _id: Long = id
        private set

    override var state: NodeState = when(state) {
        EntityState.NEW -> StateNew()
        EntityState.PERSISTED -> StatePersisted()
        EntityState.MODIFIED -> StateModified()
        EntityState.DETACHED -> StateDetached
        else -> StateRemoved
    }

    companion object {
        fun createForNewNode(
            mapper: Mapper, id: Long, label: String,
            propsDiff: HashMap<String, Value>
        ): NodeController = NodeController(
            mapper, id, label, propsDiff,
            outRefCount = hashMapOf(),
            state = EntityState.NEW
        )

        fun createForDBNode(
            mapper: Mapper, id: Long, label: String,
            outRefCount: MutableMap<String, Int> = hashMapOf()
        ): NodeController = NodeController(
            mapper, id, label,
            propsDiff = hashMapOf(),
            outRefCount = outRefCount,
            state = EntityState.PERSISTED
        )
    }

    /////////////////////////////////////
    override fun onCreate(id: Long) {
        this._id = id
        this.state = StatePersisted()
        propsDiff.clear()
    }

    override fun onUpdate() {
        state = StatePersisted()
        propsDiff.clear()
    }

    override fun onRemove() {
        propsDiff.clear()
        props.clear()
        outRefCount.clear()
        state = StateRemoved
    }

    override fun onDetach() {
        propsDiff.clear()
        props.clear()
        outRefCount.clear()
        state = StateDetached
    }

    //////////////////////////////////////////
    override fun readPropertyFromDB(name: String): Value {
        return mapper.readNodeProperty(_id, name)
    }

    override fun isPropertyUnique(name: String, value: Any, dbValue: Value): Boolean {
        return mapper.isPropertyUniqueForCacheNode(label, name, value) &&
            mapper.isPropertyUniqueForDBNode(label, name, dbValue)
    }

    override fun updateEntity() {
        mapper.updateNode(_id, propsDiff)
        state = StateModified()
    }
    ////////////////////////////////////////////

    override fun createChild(rType: String, label: String): INodeController {
        return state.createChild(label, rType)
    }

    override fun createChild(rType: String, label: String, upperBound: Int): INodeController {
        return state.createChild(label, rType, upperBound)
    }

    override fun createOutRef(rType: String, endNode: INodeEntity, upperBound: Int) {
        state.createOutRef(rType, endNode, upperBound)
    }

    override  fun createOutRef(rType: String, endNode: INodeEntity)/*: IRelationshipController*/ {
        state.createOutRef(rType, endNode)
    }

    override fun remove() {
        state.remove()
    }

    override fun unload() {
        state.unload()
    }

    override fun removeChild(rType: String, childNode: INodeEntity) {
        state.removeChild(rType, childNode)
    }

    override fun removeChild(rType: String, childNode: INodeEntity, lowerBound: Int) {
        state.removeChild(rType, childNode, lowerBound)
    }

    override  fun removeOutRef(rType: String, endNode: INodeEntity) {
        state.removeOutRef(rType, endNode)
    }

    override fun removeOutRef(rType: String, endNode: INodeEntity, lowerBound: Int) {
        state.removeOutRef(rType, endNode, lowerBound)
    }

    override fun loadChildren(
        rType: String,
        endLabel: String,
        filter: String,
        limit: Int
    ) : List<INodeController> {
        return state.loadChildren(rType, endLabel, limit, filter)
    }

    interface NodeState : IPropertyAccessor {
        fun remove()
        fun unload()

        fun createChild(label: String, rType: String): INodeController
        fun createChild(label: String, rType: String, upperBound: Int): INodeController

        fun createOutRef(rType: String, end: INodeEntity)
        fun createOutRef(rType: String, end: INodeEntity, upperBound: Int)

        fun removeChild(rType: String, n: INodeEntity)
        fun removeChild(rType: String, n: INodeEntity, loverBound: Int)

        fun removeOutRef(rType: String, end: INodeEntity)
        fun removeOutRef(rType: String, end: INodeEntity, loverBound: Int)

        //fun removeInputRef(rType: String, startNode: INodeEntity)
        //fun removeInputRef(rType: String, startID: Long)
        //fun _createInputRef(rType: String, start: INodeController)//: IRelationshipController
        //fun _createInputRef(rType: String, start: Long): IRelationshipController
        //fun _createOutputRef(rType: String, end: INodeEntity): IRelationshipController
        //fun _createOutputRef(rType: String, end: Long): IRelationshipController

        fun loadChildren(
            rType: String,
            endLabel: String,
            limit: Int = 100,
            filter: String = ""
        ): List<INodeController>

        //fun getChildrenFromCache(rType: String): Sequence<INodeController>
        fun getState(): EntityState
    }

    private abstract inner class StateActive : NodeState {
        override fun createChild(label: String, rType: String): INodeController {
            return mapper.createChild(this@NodeController, rType, label)
        }

        override fun createChild(label: String, rType: String, upperBound: Int): INodeController {
            val count = outRefCount.getOrDefault(rType, 0)
            return if (upperBound - count > 0) {
                outRefCount[rType] = count + 1
                mapper.createChild(this@NodeController, rType, label)
            } else throw UpperBoundExceedException(upperBound, rType)
        }

        override fun createOutRef(rType: String, end: INodeEntity) {
            mapper.createRelationship(this@NodeController, rType, end)
        }

        override fun createOutRef(rType: String, end: INodeEntity, upperBound: Int) {
            val count = outRefCount.getOrDefault(rType, 0)
            if (upperBound - count > 0) {
                outRefCount[rType] = count + 1
                createOutRef(rType, end)
            } else throw UpperBoundExceedException(upperBound, rType)
        }

        override fun removeChild(rType: String, n: INodeEntity, loverBound: Int) {
            val count = outRefCount.getOrDefault(rType, 0)
            if (count - loverBound > 0) {
                outRefCount[rType] = count - 1
                removeChild(rType, n)
            } else throw LoverBoundExceedException(loverBound, rType)
        }

        override fun removeOutRef(rType: String, end: INodeEntity, loverBound: Int) {
            val count = outRefCount.getOrDefault(rType, 0)
            if (count - loverBound > 0) {
                outRefCount[rType] = count - 1
                removeOutRef(rType, end)
            } else throw LoverBoundExceedException(loverBound, rType)
        }
    }

    private inner class StateNew : StateActive(), IPropertyAccessor by NewPropertyAccessor() {
        override fun remove() { //remove from bufferedCreator
            mapper.removeNode(this@NodeController)
            state = StateRemoved
        }

        override fun unload() { //unload from nodesToCreate map
            mapper.unload(this@NodeController)
            state = StateDetached
        }

        override fun removeChild(rType: String, n: INodeEntity) {
            mapper.removeChild(_id, rType, n)
        }

        override fun removeOutRef(rType: String, end: INodeEntity) {
            if (end is INodeController) {
                mapper.removeRelationship(_id, rType, end)
            } else throw Exception("EndNode $end must be instance of INodeController")
        }

        override fun loadChildren(
            rType: String,
            endLabel: String,
            limit: Int,
            filter: String
        ): List<INodeController> {
            TODO("Not yet implemented for new Node")
        }

        override fun getState() = EntityState.NEW
    }

    private open inner class StatePersisted : StateActive(), IPropertyAccessor by PersistedPropertyAccessor() {
        override fun remove() {
            mapper.removeNode(_id)
            state = StateRemoved
        }

        override fun unload() {
            mapper.unload(_id)
            state = StateDetached
        }

        override fun removeChild(rType: String, n: INodeEntity) {
            if (n is INodeController) {
                when (n.getState()) {
                    EntityState.NEW -> mapper.removeChild(_id, rType, n)
                    EntityState.PERSISTED,
                    EntityState.MODIFIED -> mapper.removeChild(_id, rType, n._id)
                    else -> throw Exception("End node was removed or unloaded")
                }
            } else throw Exception("object $n must be instance of INodeController")
        }

        override fun removeOutRef(rType: String, end: INodeEntity) {
            if (end is INodeController) {
                when (end.getState()) {
                    EntityState.NEW -> mapper.removeRelationship(_id, rType, end)
                    EntityState.PERSISTED,
                    EntityState.MODIFIED -> mapper.removeRelationship(_id, rType, end._id)
                    else -> throw Exception("End node was removed or unloaded")
                }
            } else throw Exception("object $end must be instance of INodeController")
        }

        override fun loadChildren(
            rType: String,
            endLabel: String,
            limit: Int,
            filter: String
        ): List<INodeController> {
            return mapper.loadConnectedNodes(_id, rType, endLabel, filter, limit)
        }

        override fun getState() = EntityState.PERSISTED
    }

    private inner class StateModified : StatePersisted() {
        override fun getState() = EntityState.MODIFIED
    }

    private abstract class StateNotActive(private val exceptionMsg: String): NotActivePropertyAccessor(exceptionMsg), NodeState {
        override fun remove() = throw Exception(exceptionMsg)
        override fun unload() = throw Exception(exceptionMsg)
        override fun createChild(label: String, rType: String) = throw Exception(exceptionMsg)
        override fun createChild(label: String, rType: String, upperBound: Int) =
            throw Exception(exceptionMsg)

        override fun createOutRef(rType: String, end: INodeEntity) = throw Exception(exceptionMsg)
        override fun createOutRef(rType: String, end: INodeEntity, upperBound: Int) =
            throw Exception(exceptionMsg)

        override fun removeChild(rType: String, n: INodeEntity) = throw Exception(exceptionMsg)
        override fun removeChild(rType: String, n: INodeEntity, loverBound: Int) =
            throw Exception(exceptionMsg)

        override fun removeOutRef(rType: String, end: INodeEntity) = throw Exception(exceptionMsg)
        override fun removeOutRef(rType: String, end: INodeEntity, loverBound: Int) =
            throw Exception(exceptionMsg)

        override fun loadChildren(
            rType: String,
            endLabel: String,
            limit: Int,
            filter: String
        ): List<INodeController> {
            throw Exception(exceptionMsg)
        }
    }

    private object StateRemoved : StateNotActive(
        "Node '$this' was removed. Cannot perform operation on removed node"
    ) {
        override fun getState() = EntityState.REMOVED
    }

    private object StateDetached : StateNotActive(
        "Node '$this' was unloaded. Cannot perform operation on unloaded node"
    ) {
        override fun getState() = EntityState.DETACHED
    }
}