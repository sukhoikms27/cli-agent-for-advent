package com.cliagent.memory

import com.cliagent.llm.model.ChatMessage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class JsonChatStoreConcurrencyTest {

    @TempDir
    lateinit var chatsDir: Path

    @Test
    fun `concurrent saveMessage does not lose messages`() = runTest {
        JsonChatStore(chatsDir).use { store ->
            val chat = store.createChat()

            // 50 параллельных писателей, каждый кладёт своё сообщение в один чат.
            // Без сериализации read-modify-write часть записей теряется.
            val n = 50
            coroutineScope {
                (1..n).map { i ->
                    async {
                        store.saveMessage(
                            chat.id,
                            ChatMessage(role = "user", content = "msg-$i")
                        )
                    }
                }.awaitAll()
            }

            val history = store.loadHistory(chat.id)
            assertEquals(n, history.size, "все сообщения должны сохраниться")
            assertEquals(
                (1..n).map { "msg-$it" }.toSet(),
                history.map { it.content }.toSet()
            )
        }
    }
}
