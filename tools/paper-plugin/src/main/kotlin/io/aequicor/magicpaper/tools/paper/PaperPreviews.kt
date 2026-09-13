package io.aequicor.magicpaper.tools.paper

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.visualization.engine.ir.model.PropValue

@Preview(name = "Button default", group = "Paper plugin", widthDp = 280, heightDp = 72)
@Composable
fun PaperButtonPreview() = PaperFixturePreview(paperFixtures.single { it.definition.id == "PaperButton" }.definition.instance("paper"), Modifier.fillMaxSize())

@Preview(name = "Field error", group = "Paper plugin", widthDp = 280, heightDp = 140)
@Composable
fun PaperFieldErrorPreview() = PaperFixturePreview(paperFixtures.single { it.definition.id == "PaperField" }.definition.instance("paper").let { it.copy(variant = it.variant + ("state" to "ERROR")) }, Modifier.fillMaxSize())

@Preview(name = "Composer narrow and large text", group = "Paper plugin", widthDp = 240, heightDp = 220)
@Composable
fun PaperComposerNarrowPreview() = PaperFixturePreview(paperFixtures.single { it.definition.id == "PaperWorkspaceComposer" }.definition.instance("paper").let { it.copy(variant = it.variant + ("textScale" to "2")) }, Modifier.fillMaxSize())

@Preview(name = "Empty field", group = "Paper plugin", widthDp = 280, heightDp = 116)
@Composable
fun PaperEmptyFieldPreview() = PaperFixturePreview(paperFixtures.single { it.definition.id == "PaperField" }.definition.instance("paper").let { it.copy(props = it.props + ("text" to PropValue.Text(""))) }, Modifier.fillMaxSize())
