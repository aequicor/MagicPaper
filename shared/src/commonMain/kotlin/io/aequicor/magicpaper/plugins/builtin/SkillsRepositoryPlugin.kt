package io.aequicor.magicpaper.plugins.builtin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.data.skills.SkillStore
import io.aequicor.magicpaper.domain.CatalogEntry
import io.aequicor.magicpaper.domain.Skill
import io.aequicor.magicpaper.domain.SkillCatalog
import io.aequicor.magicpaper.domain.SkillInstaller
import io.aequicor.magicpaper.domain.SkillSource
import io.aequicor.magicpaper.plugins.MagicPlugin
import kotlinx.coroutines.launch

/**
 * Плагин «Лавка навыков»: магазин проверенных временем скиллов.
 * Каталог живёт в коде (офлайн), установка — в один клик с политикой
 * конфликтов: навык из другого источника с тем же именем не перезаписывается.
 */
class SkillsRepositoryPlugin(
    private val catalog: SkillCatalog,
    private val installer: SkillInstaller,
    private val store: SkillStore,
) : MagicPlugin {
    override val id = "skill-shop"
    override val title = "Лавка навыков"
    override val description = "Магазин проверенных навыков: установка в один клик."
    override val icon = "⚜"

    @Composable
    override fun Content() {
        val scope = rememberCoroutineScope()
        val installed by store.skills.collectAsState()
        var query by rememberSaveable { mutableStateOf("") }
        var entries by remember { mutableStateOf<List<CatalogEntry>>(emptyList()) }
        var notice by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(query) {
            entries = catalog.search(query, limit = 20)
        }

        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Text(icon + " " + title, style = MaterialTheme.typography.titleMedium)
            Text(
                "Проверенные навыки устанавливаются в один клик и сразу подхватываются агентом.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Найти навык…") },
                singleLine = true,
            )
            notice?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text("Каталог", style = MaterialTheme.typography.titleSmall)
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(entries, key = { it.id }) { entry ->
                    CatalogRow(entry, installed) {
                        scope.launch {
                            when (val outcome = installer.installFromCatalog(entry)) {
                                is SkillInstaller.Outcome.Installed ->
                                    notice = if (outcome.updated) {
                                        "Навык «${entry.name}» обновлён."
                                    } else {
                                        "Навык «${entry.name}» установлен."
                                    }

                                is SkillInstaller.Outcome.Conflict -> notice = outcome.message
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Библиотека (${installed.size})", style = MaterialTheme.typography.titleSmall)
            if (installed.isEmpty()) {
                Text(
                    "Пока пусто. Установите навык из каталога выше.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            installed.forEach { skill ->
                LibraryRow(skill, onToggle = { enabled ->
                    scope.launch { store.save(skill.copy(enabled = enabled)) }
                }, onDelete = {
                    scope.launch { store.delete(skill.id) }
                })
            }
        }
    }

    @Composable
    private fun CatalogRow(entry: CatalogEntry, installed: List<Skill>, onInstall: () -> Unit) {
        val existing = installed.firstOrNull {
            it.nameKey == entry.nameKey && it.source == SkillSource.CATALOG
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surface)
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    entry.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onInstall) {
                Text(if (existing != null) "Обновить" else "Установить")
            }
        }
    }

    @Composable
    private fun LibraryRow(skill: Skill, onToggle: (Boolean) -> Unit, onDelete: () -> Unit) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(skill.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (skill.source == SkillSource.CATALOG) "из лавки" else "создан агентом",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = skill.enabled, onCheckedChange = onToggle)
            TextButton(onClick = onDelete) { Text("✕") }
        }
    }
}
