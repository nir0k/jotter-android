package io.github.nir0k.jotter.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import io.github.nir0k.jotter.core.format.Collection
import io.github.nir0k.jotter.core.format.Rule
import io.github.nir0k.jotter.core.format.RuleAction
import io.github.nir0k.jotter.core.format.RuleCondition
import io.github.nir0k.jotter.core.format.RuleConditionKind
import io.github.nir0k.jotter.core.format.newId
import io.github.nir0k.jotter.core.format.normalizedTag
import io.github.nir0k.jotter.core.format.now
import io.github.nir0k.jotter.core.format.tagKey
import io.github.nir0k.jotter.core.organize.Organizer
import io.github.nir0k.jotter.core.organize.isSupported
import io.github.nir0k.jotter.data.library.LibrarySession
import io.github.nir0k.jotter.data.library.LibraryState
import io.github.nir0k.jotter.ui.TagsDialog
import io.github.nir0k.jotter.ui.collectionChoices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.nir0k.jotter.designsystem.components.AddButton
import io.github.nir0k.jotter.designsystem.components.AppTextField
import io.github.nir0k.jotter.designsystem.components.AppTopBar
import io.github.nir0k.jotter.designsystem.components.Choice
import io.github.nir0k.jotter.designsystem.components.ChoiceDialog
import io.github.nir0k.jotter.designsystem.components.ConfirmDialog
import io.github.nir0k.jotter.designsystem.components.EmptyState
import io.github.nir0k.jotter.designsystem.components.ErrorState
import io.github.nir0k.jotter.designsystem.components.IconAction
import io.github.nir0k.jotter.designsystem.components.InlineError
import io.github.nir0k.jotter.designsystem.components.LoadingState
import io.github.nir0k.jotter.designsystem.components.MenuAction
import io.github.nir0k.jotter.designsystem.components.MultiChoiceDialog
import io.github.nir0k.jotter.designsystem.components.OverflowMenuButton
import io.github.nir0k.jotter.designsystem.components.PickerField
import io.github.nir0k.jotter.designsystem.components.SecondaryButton
import io.github.nir0k.jotter.designsystem.components.SegmentedChoice
import io.github.nir0k.jotter.designsystem.components.SettingsSection
import io.github.nir0k.jotter.designsystem.components.SwitchRow
import io.github.nir0k.jotter.designsystem.icons.AppIcons
import io.github.nir0k.jotter.designsystem.theme.Dimens
import io.github.nir0k.jotter.data.R as DataR
import io.github.nir0k.jotter.ui.R as UiR
import io.github.nir0k.jotter.ui.rulesFiledMessage

// The Rules screen and the rule form: rules file notes into collections by their tags.

/** The start of the argument of the rule form for a new rule, followed by the collection it files into, if any. */
private const val NewRulePrefix = "+"

/** Returns the argument of the rule form for a new rule, filing into [collection] when given. */
fun newRuleArg(collection: String? = null): String = NewRulePrefix + collection.orEmpty()

/** Describes a rule in its row: how many conditions, what it does and whether it stops. */
@Composable
private fun ruleSummary(rule: Rule, collections: List<Collection>): String {
    val names = rule.action.collections.mapNotNull { id -> collections.firstOrNull { it.id == id }?.name }
    val targets = names.joinToString(", ").ifEmpty { stringResource(R.string.rule_deleted_collection) }
    return listOfNotNull(
        pluralStringResource(R.plurals.rule_conditions, rule.conditions.size, rule.conditions.size),
        stringResource(if (rule.action.move) R.string.rule_moves_to else R.string.rule_adds_to, targets),
        stringResource(R.string.rule_stops).takeIf { rule.stop },
    ).joinToString(" · ")
}

/**
 * RulesScreen lists the rules in the order they run, each with its switch, name and summary;
 * tapping a rule opens its form, and its ⋮ moves it, applies it to existing notes or deletes it
 * after a confirmation. A rule with conditions this client does not support says so and cannot be
 * edited or applied; it can still be switched, moved and deleted. A read-only library shows the
 * rules without controls.
 *
 * @param rules The rules, null while they are read.
 * @param failed Whether the rules file could not be read.
 * @param collections The collection tree, for the summaries.
 * @param readOnly Whether the library cannot be changed.
 * @param onEnable Switches a rule on or off.
 * @param onMove Moves a rule one place up (-1) or down (1).
 * @param onApply Applies a rule to existing notes; the caller confirms it with the count.
 * @param onDelete Deletes a rule, already confirmed.
 * @param onEdit Opens a rule's form.
 * @param onNew Opens the form of a new rule.
 * @param onRetry Reads the rules again.
 */
@Composable
fun RulesScreen(
    rules: List<Rule>?,
    failed: Boolean,
    collections: List<Collection>,
    readOnly: Boolean,
    onEnable: (Rule, Boolean) -> Unit,
    onMove: (Rule, Int) -> Unit,
    onApply: (Rule) -> Unit,
    onDelete: (Rule) -> Unit,
    onEdit: (Rule) -> Unit,
    onNew: () -> Unit,
    onRetry: () -> Unit,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
) {
    var deleting by remember { mutableStateOf<Rule?>(null) }
    Scaffold(
        topBar = { AppTopBar(stringResource(R.string.settings_rules), onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = { if (!readOnly && rules != null) AddButton(stringResource(R.string.rule_new), onNew) },
    ) { padding ->
        val modifier = Modifier.padding(padding).fillMaxSize()
        when {
            failed -> ErrorState(stringResource(R.string.rules_read_failed), onRetry, modifier)
            rules == null -> LoadingState(modifier)
            rules.isEmpty() -> EmptyState(
                AppIcons.Rules,
                stringResource(R.string.rules_empty_title),
                stringResource(R.string.rules_empty_text),
                modifier,
                actionText = stringResource(R.string.rule_new).takeIf { !readOnly },
                onAction = onNew.takeIf { !readOnly },
            )
            else -> LazyColumn(modifier, contentPadding = PaddingValues(bottom = Dimens.ScreenPadding)) {
                itemsIndexed(rules, key = { _, rule -> rule.id }) { index, rule ->
                    val menu = if (readOnly) emptyList() else listOfNotNull(
                        MenuAction(stringResource(R.string.rule_move_up), AppIcons.ArrowUp) { onMove(rule, -1) }.takeIf { index > 0 },
                        MenuAction(stringResource(R.string.rule_move_down), AppIcons.ArrowDown) { onMove(rule, 1) }.takeIf { index < rules.lastIndex },
                        MenuAction(stringResource(R.string.rule_apply), AppIcons.Play) { onApply(rule) }.takeIf { rule.isSupported },
                        MenuAction(stringResource(UiR.string.delete), AppIcons.Delete, destructive = true) { deleting = rule },
                    )
                    RuleRow(rule, collections, readOnly, { onEnable(rule, it) }, { onEdit(rule) }.takeIf { !readOnly && rule.isSupported }, menu)
                    HorizontalDivider()
                }
            }
        }
    }
    deleting?.let { rule ->
        ConfirmDialog(
            title = stringResource(R.string.rule_delete_title, rule.name),
            text = stringResource(R.string.rule_delete_text),
            confirmText = stringResource(UiR.string.delete),
            onConfirm = {
                deleting = null
                onDelete(rule)
            },
            onDismiss = { deleting = null },
            destructive = true,
        )
    }
}

/**
 * One rule of the list: its switch, name and summary, or the notice of conditions this client
 * does not support, and its ⋮.
 *
 * @param onOpen Opens the rule's form, or null when it cannot be edited.
 */
@Composable
private fun RuleRow(rule: Rule, collections: List<Collection>, readOnly: Boolean, onEnable: (Boolean) -> Unit, onOpen: (() -> Unit)?, menu: List<MenuAction>) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val enableLabel = stringResource(R.string.rule_enable, rule.name)
    Row(
        Modifier.fillMaxWidth().then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)
            .heightIn(min = Dimens.TouchTarget).padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.CardInnerGap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.CardInnerGap),
    ) {
        Switch(rule.enabled, onEnable, enabled = !readOnly, modifier = Modifier.semantics { contentDescription = enableLabel })
        Column(Modifier.weight(1f)) {
            Text(rule.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (rule.isSupported) ruleSummary(rule, collections) else stringResource(R.string.rule_unsupported),
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
        }
        if (menu.isNotEmpty()) OverflowMenuButton(menu, contentDescription = stringResource(UiR.string.tree_actions, rule.name))
    }
}

/**
 * Rules is the Rules screen wired to the library: changes apply at once to the rules as stored
 * now, Apply to existing notes is confirmed with the number of notes it changes and offers Undo.
 *
 * @param collections The collection tree.
 * @param onEdit Opens the form of a rule, by its identifier or [newRuleArg].
 */
@Composable
fun Rules(open: LibraryState.Open, session: LibrarySession, collections: List<Collection>, onEdit: (String) -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    var version by remember { mutableIntStateOf(0) }
    // The list is read again after every change, as a change of the rules alone leaves the index as it was.
    val load by produceState<Result<List<Rule>>?>(null, open.library, open.revision, version) {
        value = withContext(Dispatchers.IO) { runCatching { open.library.readRules() } }
    }
    var applying by remember { mutableStateOf<Pair<Rule, List<String>>?>(null) }
    val failed = stringResource(UiR.string.organize_failed)
    val untitled = stringResource(DataR.string.note_untitled)

    fun change(update: (List<Rule>) -> List<Rule>) {
        scope.launch {
            if (session.organize { it.updateRules(update) } == null) snackbar.showSnackbar(failed)
            version++
        }
    }

    RulesScreen(
        rules = load?.getOrNull(),
        failed = load?.isFailure == true,
        collections = collections,
        readOnly = open.library.isReadOnly,
        onEnable = { rule, on -> change { rules -> rules.map { if (it.id == rule.id) it.copy(enabled = on) else it } } },
        onMove = { rule, step ->
            change { rules ->
                val from = rules.indexOfFirst { it.id == rule.id }
                val to = from + step
                if (from < 0 || to !in rules.indices) rules else rules.toMutableList().apply { add(to, removeAt(from)) }
            }
        },
        onApply = { rule ->
            scope.launch {
                val ids = withContext(Dispatchers.IO) { runCatching { Organizer(open.library, open.index).notesForRule(rule) }.getOrNull() }
                when {
                    ids == null -> snackbar.showSnackbar(failed)
                    ids.isEmpty() -> snackbar.showSnackbar(resources.getString(R.string.rule_apply_none))
                    else -> applying = rule to ids
                }
            }
        },
        onDelete = { rule -> change { rules -> rules.filter { it.id != rule.id } } },
        onEdit = { onEdit(it.id) },
        onNew = { onEdit(newRuleArg()) },
        onRetry = { version++ },
        snackbar = snackbar,
        onBack = onBack,
    )
    applying?.let { (rule, ids) ->
        ConfirmDialog(
            title = stringResource(R.string.rule_apply_title, rule.name),
            text = pluralStringResource(R.plurals.rule_apply_text, ids.size, ids.size),
            confirmText = stringResource(R.string.rule_apply_action),
            onConfirm = {
                applying = null
                scope.launch {
                    val changes = session.organize { it.applyRule(rule, ids) }
                    when {
                        changes == null -> snackbar.showSnackbar(failed)
                        changes.isEmpty() -> Unit
                        snackbar.showSnackbar(rulesFiledMessage(resources, changes, collections, untitled), actionLabel = resources.getString(UiR.string.undo)) ==
                            SnackbarResult.ActionPerformed ->
                            if (session.organize { it.undoRules(changes) } == null) snackbar.showSnackbar(failed)
                    }
                }
            },
            onDismiss = { applying = null },
        )
    }
}

/** Keeps a form's conditions across rotation as "kind:value" texts. */
private val ConditionsSaver = listSaver<List<RuleCondition>, String>(
    save = { conditions -> conditions.map { "${it.kind.name}:${it.value}" } },
    restore = { saved -> saved.map { RuleCondition(RuleConditionKind.valueOf(it.substringBefore(':')), it.substringAfter(':')) } },
)

/** A dialog of the rule form. */
private enum class RuleDialog { Tags, Targets, Discard }

/**
 * RuleFormScreen is the full-screen form of a rule: its name, whether all or any conditions
 * must hold, the conditions (has a tag, chosen from the library's tags or typed, or has no
 * tags), the action with its collections and Stop after this rule. Save checks the fields and
 * shows what is missing in place; leaving with changes asks to discard them.
 *
 * @param initial The rule as stored, or a new one.
 * @param isNew Whether the rule is new, which titles the form.
 * @param collections The collection tree, the action's targets.
 * @param knownTags The library's tags, offered for the conditions.
 * @param onSave Receives the rule to store; the caller closes the form once it is saved.
 * @param snackbar Messages of a failed save.
 */
@Composable
fun RuleFormScreen(
    initial: Rule,
    isNew: Boolean,
    collections: List<Collection>,
    knownTags: List<String>,
    onSave: (Rule) -> Unit,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initial.name) }
    var matchAll by rememberSaveable { mutableStateOf(initial.matchAll) }
    var conditions by rememberSaveable(stateSaver = ConditionsSaver) { mutableStateOf(initial.conditions) }
    var move by rememberSaveable { mutableStateOf(initial.action.move) }
    var targets by rememberSaveable { mutableStateOf(initial.action.collections) }
    var stop by rememberSaveable { mutableStateOf(initial.stop) }
    var checked by rememberSaveable { mutableStateOf(false) }
    var dialog by rememberSaveable { mutableStateOf<RuleDialog?>(null) }

    val existing = collections.map { it.id }.toSet()
    val chosen = targets.filter { it in existing }
    val draft = initial.copy(
        name = name.trim(), matchAll = matchAll, conditions = conditions,
        action = RuleAction(move, if (move) targets.take(1) else targets), stop = stop,
    )
    val changed = draft != initial
    val leave = { if (changed) dialog = RuleDialog.Discard else onBack() }
    BackHandler(onBack = leave)

    val nameError = stringResource(R.string.rule_name_error).takeIf { checked && draft.name.isEmpty() }
    val conditionsError = stringResource(R.string.rule_conditions_error).takeIf { checked && conditions.isEmpty() }
    val actionError = when {
        !checked -> null
        collections.isEmpty() -> stringResource(R.string.rule_no_collections)
        chosen.isEmpty() -> stringResource(R.string.rule_collection_error)
        else -> null
    }
    val tagValues = conditions.filter { it.kind == RuleConditionKind.TagEquals }.map { normalizedTag(it.value) }
    val choices = remember(collections) { collectionChoices(collections) }

    Scaffold(
        topBar = {
            AppTopBar(
                stringResource(if (isNew) R.string.rule_new else R.string.rule_edit),
                onBack = leave,
                actions = {
                    TextButton(onClick = {
                        checked = true
                        if (draft.name.isNotEmpty() && conditions.isNotEmpty() && chosen.isNotEmpty()) onSave(draft)
                    }) { Text(stringResource(io.github.nir0k.jotter.designsystem.R.string.ds_save)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            // The form ends above the keyboard, so a focused field scrolls into view.
            Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize().verticalScroll(rememberScrollState()).padding(Dimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.SectionGap),
        ) {
            AppTextField(name, { name = it }, stringResource(R.string.rule_name), error = nameError)
            SegmentedChoice(
                stringResource(R.string.rule_match),
                listOf(Choice(true, stringResource(R.string.rule_match_all)), Choice(false, stringResource(R.string.rule_match_any))),
                matchAll,
                { matchAll = it },
            )
            SettingsSection(stringResource(R.string.rule_conditions)) {
                conditions.forEachIndexed { index, condition ->
                    val text = if (condition.kind == RuleConditionKind.NoTags) stringResource(R.string.rule_condition_no_tags)
                    else stringResource(R.string.rule_condition_tag, normalizedTag(condition.value))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.CardInnerGap)) {
                        Icon(AppIcons.Tag, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(Dimens.IconSecondary))
                        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        IconAction(AppIcons.Close, stringResource(R.string.rule_condition_remove, text), {
                            conditions = conditions.filterIndexed { i, _ -> i != index }
                        })
                    }
                }
                conditionsError?.let { InlineError(it) }
                SecondaryButton(stringResource(R.string.rule_add_tags), { dialog = RuleDialog.Tags }, Modifier.fillMaxWidth(), icon = AppIcons.Add)
                if (conditions.none { it.kind == RuleConditionKind.NoTags }) {
                    SecondaryButton(
                        stringResource(R.string.rule_condition_no_tags),
                        { conditions = conditions + RuleCondition(RuleConditionKind.NoTags) },
                        Modifier.fillMaxWidth(),
                        icon = AppIcons.Add,
                    )
                }
            }
            SettingsSection(stringResource(R.string.rule_action)) {
                SegmentedChoice(
                    null,
                    listOf(Choice(false, stringResource(R.string.rule_action_add)), Choice(true, stringResource(R.string.rule_action_move))),
                    move,
                    { move = it },
                )
                val labels = (if (move) chosen.take(1) else chosen).mapNotNull { id -> choices.firstOrNull { it.key == id }?.label }
                PickerField(
                    stringResource(if (move) R.string.rule_collection else R.string.rule_collections),
                    labels.joinToString(", "),
                    { dialog = RuleDialog.Targets },
                    placeholder = stringResource(R.string.rule_choose),
                    leadingIcon = AppIcons.Folder,
                )
                actionError?.let { InlineError(it) }
                SwitchRow(stringResource(R.string.rule_stop), stop, { stop = it }, subtitle = stringResource(R.string.rule_stop_text))
            }
            Text(stringResource(R.string.rule_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    when (dialog) {
        RuleDialog.Tags -> TagsDialog(stringResource(R.string.rule_tags_title), knownTags, tagValues, { picked ->
            // Tags kept stay in their place, new ones follow, and the other conditions stay.
            val keys = picked.map(::tagKey).toSet()
            val kept = conditions.filter { it.kind != RuleConditionKind.TagEquals || tagKey(normalizedTag(it.value)) in keys }
            val present = kept.filter { it.kind == RuleConditionKind.TagEquals }.map { tagKey(normalizedTag(it.value)) }.toSet()
            conditions = kept + picked.filter { tagKey(it) !in present }.map { RuleCondition(RuleConditionKind.TagEquals, it) }
        }) { dialog = null }
        RuleDialog.Targets -> if (move) {
            ChoiceDialog(stringResource(R.string.rule_collection), choices, chosen.firstOrNull(), { targets = listOf(it) }) { dialog = null }
        } else {
            MultiChoiceDialog(stringResource(R.string.rule_collections), choices, chosen.toSet(), { picked ->
                targets = chosen.filter { it in picked } + choices.map { it.key }.filter { it in picked && it !in chosen }
            }) { dialog = null }
        }
        RuleDialog.Discard -> ConfirmDialog(
            title = stringResource(R.string.rule_discard_title),
            text = stringResource(R.string.rule_discard_text),
            confirmText = stringResource(R.string.rule_discard_action),
            onConfirm = {
                dialog = null
                onBack()
            },
            onDismiss = { dialog = null },
            destructive = true,
        )
        null -> Unit
    }
}

/**
 * RuleForm is the rule form wired to the library: it reads the rule, or starts a new one filing
 * into the collection the argument names, and saves it into the rules as stored now.
 *
 * @param arg The rule's identifier, or [newRuleArg].
 * @param onDone Closes the form after a save.
 */
@Composable
fun RuleForm(
    open: LibraryState.Open,
    session: LibrarySession,
    arg: String,
    collections: List<Collection>,
    knownTags: List<String>,
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val failed = stringResource(UiR.string.organize_failed)
    val isNew = arg.startsWith(NewRulePrefix)
    val rule by produceState<Rule?>(null, open.library, arg) {
        value = if (isNew) {
            val time = now()
            val target = arg.removePrefix(NewRulePrefix).takeIf { it.isNotEmpty() }
            Rule(
                id = newId(), name = "", conditions = emptyList(), action = RuleAction(false, listOfNotNull(target)),
                created = time, updated = time,
            )
        } else {
            // A rule deleted meanwhile, such as by a sync run, closes the form.
            withContext(Dispatchers.IO) { runCatching { open.library.readRules() }.getOrNull()?.firstOrNull { it.id == arg } } ?: return@produceState onBack()
        }
    }
    val loaded = rule
    if (loaded == null) {
        Scaffold(topBar = { AppTopBar(stringResource(R.string.rule_edit), onBack = onBack) }) { LoadingState(Modifier.padding(it)) }
        return
    }
    RuleFormScreen(
        loaded, isNew, collections, knownTags,
        onSave = { edited ->
            scope.launch {
                val saved = session.organize { organizer ->
                    organizer.updateRules { rules -> if (rules.any { it.id == edited.id }) rules.map { if (it.id == edited.id) edited else it } else rules + edited }
                }
                if (saved == null) snackbar.showSnackbar(failed) else onDone()
            }
        },
        snackbar = snackbar,
        onBack = onBack,
    )
}
