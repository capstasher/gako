package io.github.capstasher.gako.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.capstasher.gako.engine.OpInfo
import io.github.capstasher.gako.ui.theme.EngravedLabel
import io.github.capstasher.gako.ui.theme.Gako

/** Shared navigation state for the nested menus. */
class MenuState(initial: List<String> = emptyList()) {
    var path by mutableStateOf(initial)
    var searching by mutableStateOf(false)
    var query by mutableStateOf("")
    /** Raise the keyboard only when search is opened, not when returning to it. */
    var focusSearch = false

    fun openSearch() {
        searching = true
        focusSearch = true
    }

    fun openRoot(key: String) {
        path = if (path.firstOrNull() == key) emptyList() else listOf(key)
        searching = false
    }

    fun push(key: String) {
        path = path + key
        searching = false
    }

    fun pop() {
        if (searching) searching = false else path = path.dropLast(1)
    }

    fun close() {
        path = emptyList()
        searching = false
    }
}

@Composable
fun rememberMenuState(): MenuState {
    var saved by rememberSaveable { mutableStateOf(listOf<String>()) }
    val state = remember { MenuState(saved) }
    LaunchedEffect(state.path) { saved = state.path }
    return state
}

/** Groups open; actions run and leave you where you were, ready for the next one. */
private fun runEntry(entry: MenuEntry, state: MenuState) {
    when (entry) {
        is MenuGroup -> state.push(entry.key)
        is MenuAction -> if (entry.enabled) {
            entry.onClick()
            if (entry.closesMenu) state.close()
        }
    }
}

// ---------------------------------------------------------------- compact (phones)

/** Thumb-reach menus: a root bar at the very bottom, nested tiers slide up above it. */
class History(val canUndo: Boolean, val canRedo: Boolean, val undo: () -> Unit, val redo: () -> Unit)

@Composable
fun HistoryButton(undo: Boolean, history: History) {
    val enabled = if (undo) history.canUndo else history.canRedo
    IconButton(onClick = if (undo) history.undo else history.redo, enabled = enabled) {
        Icon(
            if (undo) Icons.AutoMirrored.Outlined.Undo else Icons.AutoMirrored.Outlined.Redo,
            contentDescription = if (undo) "Undo" else "Redo",
            tint = if (enabled) Gako.Parchment else Gako.Muted.copy(alpha = 0.35f),
        )
    }
}

@Composable
fun BottomMenu(
    roots: List<MenuGroup>,
    state: MenuState,
    onSearchPick: (OpInfo) -> Unit,
    modifier: Modifier = Modifier,
    above: @Composable () -> Unit = {},
) {
    val trail = roots.resolve(state.path)
    val current = trail.lastOrNull()

    Column(modifier.fillMaxWidth().background(Gako.InkRaised)) {
        AnimatedVisibility(
            visible = current != null,
            enter = expandVertically(expandFrom = Alignment.Bottom) + fadeIn(),
            exit = shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut(),
        ) {
            if (current != null) {
                Column(Modifier.fillMaxWidth()) {
                    Breadcrumb(trail, state)
                    val panel = current.panel
                    if (panel != null) {
                        Box(Modifier.fillMaxWidth().heightIn(max = 340.dp)) { panel() }
                    } else if (state.searching) {
                        SearchPane(current.searchable, state, onSearchPick, Modifier.height(260.dp))
                    } else {
                        LazyRow(
                            modifier = Modifier.fillMaxWidth().height(TIER_HEIGHT),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            if (current.searchable.isNotEmpty()) {
                                item(key = "search") {
                                    Tile("Search", Icons.Outlined.Search, isGroup = false, enabled = true) {
                                        state.openSearch()
                                    }
                                }
                            }
                            items(current.children, key = { it.key }) { entry ->
                                Tile(entry.label, entry.icon, entry is MenuGroup,
                                    enabled = (entry as? MenuAction)?.enabled ?: true,
                                    selected = (entry as? MenuAction)?.selected == true) { runEntry(entry, state) }
                            }
                        }
                    }
                }
            }
        }
        if (current == null) above()
        HorizontalDivider(color = Gako.GoldDim.copy(alpha = 0.35f))
        Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
            roots.filter { it.inBar }.forEach { root ->
                RootTab(root, selected = state.path.firstOrNull() == root.key, Modifier.weight(1f)) {
                    state.openRoot(root.key)
                }
            }
        }
    }
}

@Composable
private fun Breadcrumb(trail: List<MenuGroup>, state: MenuState) {
    Row(
        Modifier.fillMaxWidth().height(40.dp).padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { state.pop() }) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = Gako.Muted)
        }
        Text(
            (trail.map { it.label } + if (state.searching) listOf("Search") else emptyList())
                .joinToString("  ›  ").uppercase(),
            style = EngravedLabel,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { state.close() }) {
            Icon(Icons.Outlined.Close, contentDescription = "Close menu", tint = Gako.Muted)
        }
    }
}

@Composable
private fun Tile(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, isGroup: Boolean,
                 enabled: Boolean, selected: Boolean = false, onClick: () -> Unit) {
    val alpha = if (enabled) 1f else 0.35f
    Column(
        Modifier
            .width(84.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(46.dp)
                .background((if (selected) Gako.Oxblood else Gako.InkHigh).copy(alpha = alpha), CircleShape)
                .then(if (isGroup || selected) Modifier.border(BorderStroke(1.dp, if (selected) Gako.Gold else Gako.GoldDim), CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = (if (isGroup) Gako.Gold else Gako.Parchment).copy(alpha = alpha))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = Gako.Parchment.copy(alpha = alpha),
            textAlign = TextAlign.Center,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val TIER_HEIGHT = 100.dp

@Composable
private fun RootTab(root: MenuGroup, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val tint = if (selected) Gako.Gold else Gako.Muted
    Column(
        modifier.fillMaxHeight().clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .width(48.dp)
                .height(26.dp)
                .background(if (selected) Gako.Oxblood else Color.Transparent, RoundedCornerShape(13.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(root.icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(2.dp))
        Text(root.label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1,
            overflow = TextOverflow.Clip, softWrap = false)
    }
}

// ---------------------------------------------------------------- shared search

@Composable
fun SearchPane(
    ops: List<OpInfo>,
    state: MenuState,
    onPick: (OpInfo) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (state.focusSearch) {
            state.focusSearch = false
            runCatching { focus.requestFocus() }
        }
    }
    val q = state.query.trim()
    val results = remember(q, ops) {
        if (q.isEmpty()) ops.sortedBy { it.title }
        else ops.filter {
            it.title.contains(q, true) || it.name.contains(q, true) || it.description.contains(q, true)
        }.sortedWith(compareBy<OpInfo>({ !it.title.contains(q, true) }, { it.title }))
    }
    Column(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = state.query,
            onValueChange = { state.query = it },
            placeholder = { Text("Search") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Gako.Gold, cursorColor = Gako.Gold),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).focusRequester(focus),
        )
        LazyColumn(Modifier.fillMaxWidth()) {
            items(results, key = { it.name }) { op ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(op) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(op.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

// ---------------------------------------------------------------- wide (landscape, tablets, unfolded)

@Composable
fun SideRail(roots: List<MenuGroup>, state: MenuState, modifier: Modifier = Modifier) {
    Column(
        modifier.width(76.dp).fillMaxHeight().background(Gako.InkRaised).verticalScroll(rememberScrollState())
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        roots.forEach { root ->
            RootTab(root, selected = state.path.firstOrNull() == root.key, Modifier.height(56.dp).fillMaxWidth()) {
                state.openRoot(root.key)
            }
        }
    }
}

@Composable
fun SideSubmenu(
    roots: List<MenuGroup>,
    state: MenuState,
    onSearchPick: (OpInfo) -> Unit,
    modifier: Modifier = Modifier,
) {
    val trail = roots.resolve(state.path)
    val current = trail.lastOrNull() ?: return
    Column(modifier.width(300.dp).fillMaxHeight().background(Gako.Ink)) {
        Breadcrumb(trail, state)
        HorizontalDivider(color = Gako.GoldDim.copy(alpha = 0.35f))
        val panel = current.panel
        if (panel != null) {
            Box(Modifier.weight(1f)) { panel() }
        } else if (state.searching) {
            SearchPane(current.searchable, state, onSearchPick, Modifier.weight(1f))
        } else {
            LazyColumn(Modifier.weight(1f)) {
                if (current.searchable.isNotEmpty()) {
                    item(key = "search") {
                        SideRow("Search", Icons.Outlined.Search, false, true) { state.openSearch() }
                    }
                }
                items(current.children, key = { it.key }) { entry ->
                    SideRow(entry.label, entry.icon, entry is MenuGroup,
                        (entry as? MenuAction)?.enabled ?: true,
                        (entry as? MenuAction)?.selected == true) { runEntry(entry, state) }
                }
            }
        }
    }
}

@Composable
private fun SideRow(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, isGroup: Boolean,
                    enabled: Boolean, selected: Boolean = false, onClick: () -> Unit) {
    val alpha = if (enabled) 1f else 0.35f
    Row(
        Modifier.fillMaxWidth().background(if (selected) Gako.Oxblood else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = (if (isGroup) Gako.Gold else Gako.Parchment).copy(alpha = alpha))
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Gako.Parchment.copy(alpha = alpha),
            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (isGroup) Icon(Icons.Outlined.ChevronRight, null, tint = Gako.GoldDim)
    }
}
