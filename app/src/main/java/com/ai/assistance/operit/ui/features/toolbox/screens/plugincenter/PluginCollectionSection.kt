package com.ai.assistance.operit.ui.features.toolbox.screens.plugincenter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun PluginCollectionSection(
    title: String,
    totalCount: Int,
    matchedCount: Int,
    query: String,
    onQueryChange: (String) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    searchPlaceholder: String = "搜索插件",
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextButton(onClick = { onExpandedChange(!expanded) }) {
                Text("${if (expanded) "▼" else "▶"} $title（$totalCount）")
            }
            OutlinedTextField(
                value = query,
                onValueChange = { value ->
                    onQueryChange(value)
                    if (value.isNotBlank()) onExpandedChange(true)
                },
                modifier = Modifier.weight(1f),
                placeholder = { Text(searchPlaceholder) },
                singleLine = true
            )
        }
        if (query.isNotBlank()) {
            Text(
                "匹配 $matchedCount / $totalCount",
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (expanded) content()
    }
}
