package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

internal val paperCodeExample = """
    `src/main/resources/application.yml`:

    ```yaml
    spring:
      ai:
        openai:
          api-key: ${'$'}{OPENAI_API_KEY}
          chat:
            options:
              model: example-model
              temperature: 0.2
    ```
""".trimIndent()

@Preview(name = "Filename in header", group = "Markdown code", widthDp = 640, heightDp = 340)
@Preview(name = "Narrow filename", group = "Markdown code", widthDp = 320, heightDp = 340)
@Preview(name = "Large text", group = "Markdown code", widthDp = 480, heightDp = 480, fontScale = 2f)
@Composable
internal fun PaperMarkdownCodePreview() = PaperTheme {
    PaperSurface(Modifier.fillMaxSize()) {
        Box(Modifier.padding(16.dp)) { PaperMarkdown(paperCodeExample, Modifier.fillMaxWidth()) }
    }
}

internal val paperCodeSequenceExample = """
    Запуск и проверка:

    ```bash
    ./gradlew bootRun
    ```

    ```bash
    curl -X POST http://localhost:8080/api/chat \
      -H "Content-Type: application/json" \
      -d '{"message":"Объясни разницу между List и Sequence в Kotlin"}'
    ```

    Пример ответа:

    ```json
    {
      "answer": "List выполняет цепочку преобразований…"
    }
    ```
""".trimIndent()

@Preview(name = "Compact code sequence", group = "Markdown code", widthDp = 640, heightDp = 360)
@Preview(name = "Narrow code sequence", group = "Markdown code", widthDp = 320, heightDp = 360)
@Preview(name = "Code sequence large text", group = "Markdown code", widthDp = 480, heightDp = 520, fontScale = 2f)
@Composable
internal fun PaperMarkdownCodeSequencePreview() = PaperTheme {
    val document by produceState<PaperMarkdownDocument?>(null) {
        value = parsePaperMarkdown(paperCodeSequenceExample)
    }
    PaperSurface(Modifier.fillMaxSize()) {
        PaperResearchReading {
            document?.let { PaperMarkdownBody(it, it.node.children, Modifier.padding(16.dp)) }
        }
    }
}

@Preview(name = "One long code card", group = "Markdown code", widthDp = 640, heightDp = 540)
@Composable
internal fun PaperMarkdownLongCodePreview() = PaperTheme {
    PaperSurface(Modifier.fillMaxSize()) {
        Box(Modifier.padding(16.dp)) {
            PaperMarkdown("```kotlin title=src/main/kotlin/Example.kt\n" +
                (1..1000).joinToString("\n") { "val value$it = $it // длинный пример" } + "\n```", Modifier.fillMaxWidth())
        }
    }
}
