package geodes.sms.nmf.neo4j.io

import org.neo4j.driver.internal.value.IntegerValue
import org.neo4j.driver.internal.value.MapValue
import org.neo4j.driver.v1.*


class Neo4jGraph private constructor(private val driver: Driver) : IGraph, NodeStateListener {

    /** It is recommended to create no more then 75 entities (nodes or refs)
     *  at a time before calling graph.save() for performance reasons */
    private val buffCapacity = 75

    /** Node aliases that appear in query within CREATE(alias) or MATCH(alias) clauses */
    //private val initedNodes = hashSetOf<INode>()

    /** Set IDs for those nodes after Graph.save() command */
    private val nodesToCreate = hashMapOf<String, GraphStateListener>()

    /** Update properties */
    private val nodesToUpdate = mutableListOf<GraphStateListener>()

    /*
     * Initial capacity calculated for creating 200 nodes or 200 refs
     * create node query line length ~32; create ref query line length ~64
     */
    private val qCreate = StringBuilder(buffCapacity * 68)
    private val qMatch = StringBuilder(buffCapacity * 36)
    private val qSet = StringBuilder()
    private val qReturn = StringBuilder(buffCapacity * 14)
    private val properties = mutableMapOf<String, Value>()

    private val TYPE_DEFAULT = "TYPE_DEFAULT"

    companion object {
        fun create(cr: DBCreadentials): IGraph =
            Neo4jGraph(GraphDatabase.driver(cr.dbUri, AuthTokens.basic(cr.username, cr.password)))
    }

    override fun createNode(label: String): INode {
        val node = Node(this)
        val alias = node.alias
        val prAlias = "pr_$alias"
        properties[prAlias] = MapValue(node.props)  //connect localProps to globalProps

        qCreate.append("CREATE ($alias")
        if (label.isNotEmpty()) qCreate.append(":$label")
        qCreate.appendln(" $$prAlias)")
        qReturn.append("$alias:ID($alias),")

        //initedNodes.add(node)
        nodesToCreate[alias] = node
        return node
    }

    override fun matchNode(id: Long): INode {
        return Node(this, id)
    }

    override fun createRelation(refType: String, start: INode, end: INode, containment: Boolean) {
        (start as Node).nodeState.register()
        (end as Node).nodeState.register()
        val validType = if (refType.isEmpty()) TYPE_DEFAULT else refType
        qCreate.appendln("CREATE (${start.alias})-[:$validType{containment:$containment}]->(${end.alias})")
    }

    /**
     * Create new relation with new endNode ( -->(newNode) ). StartNode must already exist
     * @return newNode with specified label
     */
    override fun createPath(start:INode, endLabel:String, refType:String, containment:Boolean): INode {
        (start as Node).nodeState.register()
        val validType = if (refType.isEmpty()) TYPE_DEFAULT else refType
        val end = Node(this)
        val prAlias = "pr_${end.alias}"
        properties[prAlias] = MapValue(end.props)

        qCreate.append("CREATE (${start.alias})-[:$validType{containment:$containment}]->(${end.alias}")
            .appendln(if (endLabel.isNotEmpty()) ":$endLabel $$prAlias)" else " $$prAlias)")
        qReturn.append("${end.alias}:ID(${end.alias}),")

        nodesToCreate[end.alias] = end
        return end
    }

    //// Inherited from NodeStateListener
    override fun onMatch(node: Node) {
        nodesToUpdate.add(node)
        val alias = node.alias
        val idAlias = "id_$alias"
        qMatch.appendln("MATCH ($alias) WHERE ID($alias)=$$idAlias")
        properties[idAlias] = IntegerValue(node.id)
    }

    override fun onUpdate(node: Node, props: Map<String, Value>) {
        val prAlias = "pr_${node.alias}"
        properties[prAlias] = MapValue(props)
        qSet.appendln("SET ${node.alias}+=$$prAlias")
    }
    ////

    private fun qReturn() : String {
        if (qReturn.isNotEmpty())
            qReturn.setCharAt(qReturn.length - 1, ' ')
        return "RETURN {$qReturn} AS nodeIDs"
    }


    override fun save() {
        if (nodesToCreate.isEmpty() && nodesToUpdate.isEmpty()) return

//        println("$qMatch   MATCH capacity: ${qMatch.capacity()}  length: ${qMatch.length}")
//        println("$qCreate  CREATE capacity: ${qCreate.capacity()}  length: ${qCreate.length}")
//        println("$qSet  SET capacity: ${qSet.capacity()}  length: ${qSet.length}")
//        println("${qReturn()}  RESTURN capacity: ${qReturn.capacity()}  length: ${qReturn.length}")
//        println()

        val session = driver.session(AccessMode.WRITE)
        //try {
        val map = session.writeTransaction { tx->
            val res = tx.run(Statement(qMatch.toString() + qCreate.toString() + qSet.toString()
                    + qReturn(), MapValue(properties))
            )
            res.single().get("nodeIDs").asMap(Values.ofLong())
        }

        map.forEach { (alias, id) -> nodesToCreate[alias]!!.onSave(id) }
        nodesToUpdate.forEach { it.onSave() }

        nodesToCreate.clear()
        nodesToUpdate.clear()
        qMatch.clear()  // clear() method preserves initial capacity of StringBuilder
        qCreate.clear()
        qSet.clear()
        qReturn.clear()
        properties.clear()

        //finally {
        session.close()
    }

    override fun clearDB() {
        driver.session().use { it.run(Statement("MATCH (n) DETACH DELETE n")) }
    }

    override fun close() {
        save()
        driver.close()
    }
}


/*
"CREATE (n001:Label{pr_n001})"      //length = 28  ~32

"CREATE (n001)-[:name{containment:true}]->(n002)" //length = 47   ~64

"MATCH (n001) WHERE ID(n001)=$id_n001"   //length = 36

"n001:ID(n001),"  //length = 14
 */