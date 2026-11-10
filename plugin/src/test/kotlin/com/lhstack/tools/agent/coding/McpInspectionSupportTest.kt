package com.lhstack.tools.agent.coding

import com.lhstack.tools.agent.resources.McpInspectionSupport
import com.lhstack.tools.db.entity.McpServerEntity
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

class McpInspectionSupportTest {
    @Test
    fun `tools inspection has one five second deadline across initialize and list`() {
        val python = java.io.File("/usr/bin/python3")
        assumeTrue(python.canExecute(), "requires system python3")
        val root = Files.createTempDirectory("jtools-mcp-timeout").toFile()
        try {
            val script = root.resolve("server.py")
            script.writeText(
                """
                import sys, json, time
                for line in sys.stdin:
                    request=json.loads(line)
                    if 'id' not in request: continue
                    if request['method']=='initialize':
                        time.sleep(2)
                        result={'protocolVersion':request['params']['protocolVersion'],'capabilities':{'tools':{}},'serverInfo':{'name':'slow','version':'1'}}
                    elif request['method']=='tools/list':
                        time.sleep(4)
                        result={'tools':[]}
                    else: result={}
                    print(json.dumps({'jsonrpc':'2.0','id':request['id'],'result':result}),flush=True)
                """
                    .trimIndent()
            )
            val server =
                McpServerEntity().apply {
                    name = "slow"
                    transport = "stdio"
                    command = python.path
                    args = com.google.gson.Gson().toJson(listOf(script.path))
                }
            val started = System.nanoTime()
            val error =
                assertFailsWith<IllegalStateException> {
                    McpInspectionSupport.tools(server, root.path)
                }
            val elapsed = (System.nanoTime() - started) / 1_000_000
            assertTrue(error.message.orEmpty().contains("5 秒"), error.toString())
            assertTrue(elapsed in 4500..5800, "deadline took $elapsed ms")
        } finally {
            root.deleteRecursively()
        }
    }
}
