/*
 * Copyright (c) 2012 Todoroo Inc
 *
 * See the file "LICENSE" for the full license governing this code.
 */

package com.todoroo.astrid.activity;

import static android.app.Activity.RESULT_OK;
import static androidx.core.content.ContextCompat.getColor;
import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Lists.transform;
import static com.todoroo.andlib.utility.AndroidUtilities.assertMainThread;
import static org.tasks.activities.RemoteListPicker.newRemoteListSupportPicker;
import static org.tasks.caldav.CaldavCalendarSettingsActivity.EXTRA_CALDAV_CALENDAR;
import static org.tasks.date.DateTimeUtils.newDateTime;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Parcelable;
import android.speech.RecognizerIntent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MenuItem.OnActionExpandListener;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ActionMode;
import androidx.appcompat.view.ActionMode.Callback;
import androidx.appcompat.widget.SearchView;
import androidx.appcompat.widget.SearchView.OnQueryTextListener;
import androidx.appcompat.widget.Toolbar;
import androidx.appcompat.widget.Toolbar.OnMenuItemClickListener;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.util.Pair;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.ViewModelProviders;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout.OnRefreshListener;
import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;
import com.google.android.material.snackbar.Snackbar;
import com.google.common.base.Joiner;
import com.google.common.primitives.Longs;
import com.todoroo.astrid.adapter.TaskAdapter;
import com.todoroo.astrid.adapter.TaskAdapterProvider;
import com.todoroo.astrid.api.CaldavFilter;
import com.todoroo.astrid.api.Filter;
import com.todoroo.astrid.api.GtasksFilter;
import com.todoroo.astrid.api.SearchFilter;
import com.todoroo.astrid.api.TagFilter;
import com.todoroo.astrid.core.BuiltInFilterExposer;
import com.todoroo.astrid.dao.TaskDao;
import com.todoroo.astrid.data.Task;
import com.todoroo.astrid.service.TaskCreator;
import com.todoroo.astrid.service.TaskDeleter;
import com.todoroo.astrid.service.TaskDuplicator;
import com.todoroo.astrid.service.TaskMover;
import com.todoroo.astrid.timers.TimerPlugin;
import com.todoroo.astrid.utility.Flags;
import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import io.reactivex.subjects.PublishSubject;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import org.tasks.LocalBroadcastManager;
import org.tasks.R;
import org.tasks.activities.FilterSettingsActivity;
import org.tasks.activities.GoogleTaskListSettingsActivity;
import org.tasks.activities.PlaceSettingsActivity;
import org.tasks.activities.RemoteListPicker;
import org.tasks.activities.TagSettingsActivity;
import org.tasks.caldav.CaldavCalendarSettingsActivity;
import org.tasks.data.CaldavAccount;
import org.tasks.data.CaldavCalendar;
import org.tasks.data.CaldavDao;
import org.tasks.data.Place;
import org.tasks.data.TagDataDao;
import org.tasks.data.TaskContainer;
import org.tasks.dialogs.DateTimePicker;
import org.tasks.dialogs.DialogBuilder;
import org.tasks.dialogs.SortDialog;
import org.tasks.etesync.EteSyncCalendarSettingsActivity;
import org.tasks.filters.PlaceFilter;
import org.tasks.injection.ForActivity;
import org.tasks.injection.FragmentComponent;
import org.tasks.injection.InjectingFragment;
import org.tasks.intents.TaskIntents;
import org.tasks.notifications.NotificationManager;
import org.tasks.preferences.Device;
import org.tasks.preferences.Preferences;
import org.tasks.sync.SyncAdapters;
import org.tasks.tags.TagPickerActivity;
import org.tasks.tasklist.DragAndDropRecyclerAdapter;
import org.tasks.tasklist.PagedListRecyclerAdapter;
import org.tasks.tasklist.TaskListRecyclerAdapter;
import org.tasks.tasklist.ViewHolderFactory;
import org.tasks.themes.ColorProvider;
import org.tasks.themes.ThemeColor;
import org.tasks.ui.TaskListViewModel;
import org.tasks.ui.Toaster;

public final class TaskListFragment extends InjectingFragment
    implements OnRefreshListener,
        OnMenuItemClickListener,
        OnActionExpandListener,
        OnQueryTextListener,
        Callback {

  public static final String TAGS_METADATA_JOIN = "for_tags"; // $NON-NLS-1$
  public static final String GTASK_METADATA_JOIN = "googletask"; // $NON-NLS-1$
  public static final String CALDAV_METADATA_JOIN = "for_caldav"; // $NON-NLS-1$
  public static final String ACTION_RELOAD = "action_reload";
  public static final String ACTION_DELETED = "action_deleted";
  private static final String EXTRA_SELECTED_TASK_IDS = "extra_selected_task_ids";
  private static final String EXTRA_SEARCH = "extra_search";
  private static final int VOICE_RECOGNITION_REQUEST_CODE = 1234;
  private static final String EXTRA_FILTER = "extra_filter";
  private static final String FRAG_TAG_REMOTE_LIST_PICKER = "frag_tag_remote_list_picker";
  private static final String FRAG_TAG_SORT_DIALOG = "frag_tag_sort_dialog";
  private static final String FRAG_TAG_DATE_TIME_PICKER = "frag_tag_date_time_picker";
  private static final int REQUEST_LIST_SETTINGS = 10101;
  private static final int REQUEST_MOVE_TASKS = 10103;
  private static final int REQUEST_TAG_TASKS = 10106;
  private static final int REQUEST_DUE_DATE = 10107;

  private static final int SEARCH_DEBOUNCE_TIMEOUT = 300;
  private final RefreshReceiver refreshReceiver = new RefreshReceiver();
  protected CompositeDisposable disposables;
  @Inject SyncAdapters syncAdapters;
  @Inject TaskDeleter taskDeleter;
  @Inject @ForActivity Context context;
  @Inject Preferences preferences;
  @Inject DialogBuilder dialogBuilder;
  @Inject TaskCreator taskCreator;
  @Inject TimerPlugin timerPlugin;
  @Inject ViewHolderFactory viewHolderFactory;
  @Inject LocalBroadcastManager localBroadcastManager;
  @Inject Device device;
  @Inject TaskMover taskMover;
  @Inject Toaster toaster;
  @Inject TaskAdapterProvider taskAdapterProvider;
  @Inject TaskDao taskDao;
  @Inject TaskDuplicator taskDuplicator;
  @Inject TagDataDao tagDataDao;
  @Inject CaldavDao caldavDao;
  @Inject ThemeColor defaultThemeColor;
  @Inject ColorProvider colorProvider;
  @Inject NotificationManager notificationManager;

  @BindView(R.id.swipe_layout)
  SwipeRefreshLayout swipeRefreshLayout;

  @BindView(R.id.swipe_layout_empty)
  SwipeRefreshLayout emptyRefreshLayout;

  @BindView(R.id.toolbar)
  Toolbar toolbar;

  @BindView(R.id.task_list_coordinator)
  CoordinatorLayout coordinatorLayout;

  @BindView(R.id.recycler_view)
  RecyclerView recyclerView;

  private TaskListViewModel taskListViewModel;
  private TaskAdapter taskAdapter = null;
  private TaskListRecyclerAdapter recyclerAdapter;
  private Filter filter;
  private PublishSubject<String> searchSubject = PublishSubject.create();
  private Disposable searchDisposable;
  private MenuItem search;
  private String searchQuery;
  private ActionMode mode = null;
  private ThemeColor themeColor;

  private TaskListFragmentCallbackHandler callbacks;

  static TaskListFragment newTaskListFragment(Context context, Filter filter) {
    TaskListFragment fragment = new TaskListFragment();
    Bundle bundle = new Bundle();
    bundle.putParcelable(
        EXTRA_FILTER,
        filter == null ? BuiltInFilterExposer.getMyTasksFilter(context.getResources()) : filter);
    fragment.setArguments(bundle);
    return fragment;
  }

  @Override
  public void onRefresh() {
    disposables.add(
        syncAdapters
            .sync(true)
            .doOnSuccess(
                initiated -> {
                  if (!initiated) {
                    refresh();
                  }
                })
            .delay(1, TimeUnit.SECONDS, AndroidSchedulers.mainThread())
            .subscribe(
                initiated -> {
                  if (initiated) {
                    setSyncOngoing();
                  }
                }));
  }

  private void setSyncOngoing() {
    assertMainThread();

    boolean ongoing = preferences.isSyncOngoing();

    swipeRefreshLayout.setRefreshing(ongoing);
    emptyRefreshLayout.setRefreshing(ongoing);
  }

  @Override
  public void onViewStateRestored(@Nullable Bundle savedInstanceState) {
    super.onViewStateRestored(savedInstanceState);

    if (savedInstanceState != null) {
      long[] longArray = savedInstanceState.getLongArray(EXTRA_SELECTED_TASK_IDS);
      if (longArray != null && longArray.length > 0) {
        taskAdapter.setSelected(longArray);
        startActionMode();
      }
    }
  }

  @Override
  public void onAttach(Activity activity) {
    super.onAttach(activity);

    callbacks = (TaskListFragmentCallbackHandler) activity;
  }

  @Override
  public void inject(FragmentComponent component) {
    component.inject(this);
  }

  @Override
  public void onSaveInstanceState(@NonNull Bundle outState) {
    super.onSaveInstanceState(outState);

    List<Long> selectedTaskIds = taskAdapter.getSelected();
    outState.putLongArray(EXTRA_SELECTED_TASK_IDS, Longs.toArray(selectedTaskIds));
    outState.putString(EXTRA_SEARCH, searchQuery);
  }

  @Override
  public View onCreateView(
      LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    View parent = inflater.inflate(R.layout.fragment_task_list, container, false);
    ButterKnife.bind(this, parent);

    filter = getFilter();

    themeColor = filter.tint != 0 ? colorProvider.getThemeColor(filter.tint, true) : defaultThemeColor;

    filter.setFilterQueryOverride(null);

    // set up list adapters
    taskAdapter = taskAdapterProvider.createTaskAdapter(filter);

    taskListViewModel = ViewModelProviders.of(getActivity()).get(TaskListViewModel.class);

    if (savedInstanceState != null) {
      searchQuery = savedInstanceState.getString(EXTRA_SEARCH);
    }

    boolean dragAndDrop = taskAdapter.supportsManualSorting() || preferences.showSubtasks();
    taskListViewModel.setFilter(
        searchQuery == null ? filter : createSearchFilter(searchQuery), dragAndDrop);

    recyclerAdapter =
        dragAndDrop
            ? new DragAndDropRecyclerAdapter(
                taskAdapter,
                recyclerView,
                viewHolderFactory,
                this,
                taskListViewModel.getValue(),
                taskDao)
            : new PagedListRecyclerAdapter(
                taskAdapter,
                recyclerView,
                viewHolderFactory,
                this,
                taskListViewModel.getValue(),
                taskDao);
    taskAdapter.setHelper(recyclerAdapter);
    ((DefaultItemAnimator) recyclerView.getItemAnimator()).setSupportsChangeAnimations(false);
    recyclerView.setLayoutManager(new LinearLayoutManager(context));

    taskListViewModel.observe(
        this,
        list -> {
          recyclerAdapter.submitList(list);

          if (list.isEmpty()) {
            swipeRefreshLayout.setVisibility(View.GONE);
            emptyRefreshLayout.setVisibility(View.VISIBLE);
          } else {
            swipeRefreshLayout.setVisibility(View.VISIBLE);
            emptyRefreshLayout.setVisibility(View.GONE);
          }
        });

    recyclerView.setAdapter(recyclerAdapter);

    setupRefresh(swipeRefreshLayout);
    setupRefresh(emptyRefreshLayout);

    toolbar.setTitle(filter.listingTitle);
    toolbar.setNavigationIcon(R.drawable.ic_outline_menu_24px);
    toolbar.setNavigationOnClickListener(v -> callbacks.onNavigationIconClicked());
    toolbar.setOnMenuItemClickListener(this);
    setupMenu();

    return parent;
  }

  private void setupMenu() {
    Menu menu = toolbar.getMenu();
    menu.clear();
    if (filter.hasBeginningMenu()) {
      toolbar.inflateMenu(filter.getBeginningMenu());
    }
    toolbar.inflateMenu(R.menu.menu_task_list_fragment);
    if (filter.hasMenu()) {
      toolbar.inflateMenu(filter.getMenu());
    }
    MenuItem hidden = menu.findItem(R.id.menu_show_hidden);
    MenuItem completed = menu.findItem(R.id.menu_show_completed);
    if (!taskAdapter.supportsHiddenTasks() || !filter.supportsHiddenTasks()) {
      completed.setChecked(true);
      completed.setEnabled(false);
      hidden.setChecked(true);
      hidden.setEnabled(false);
    } else {
      hidden.setChecked(preferences.getBoolean(R.string.p_show_hidden_tasks, false));
      completed.setChecked(preferences.getBoolean(R.string.p_show_completed_tasks, false));
    }
    MenuItem sortMenu = menu.findItem(R.id.menu_sort);
    if (!filter.supportsSorting()) {
      sortMenu.setEnabled(false);
      sortMenu.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
    }
    if (!preferences.showSubtasks()
        || !filter.supportSubtasks()
        || taskAdapter.supportsManualSorting()) {
      menu.findItem(R.id.menu_collapse_subtasks).setVisible(false);
      menu.findItem(R.id.menu_expand_subtasks).setVisible(false);
    }
    menu.findItem(R.id.menu_voice_add).setVisible(device.voiceInputAvailable());

    search = menu.findItem(R.id.menu_search).setOnActionExpandListener(this);
    ((SearchView) search.getActionView()).setOnQueryTextListener(this);

    themeColor.apply(toolbar);
  }

  private void openFilter(@Nullable Filter filter) {
    if (filter == null) {
      startActivity(TaskIntents.getTaskListByIdIntent(context, null));
    } else {
      startActivity(TaskIntents.getTaskListIntent(context, filter));
    }
  }

  private void searchByQuery(@Nullable String query) {
    searchQuery = query == null ? "" : query.trim();
    if (searchQuery.isEmpty()) {
      taskListViewModel.searchByFilter(
          BuiltInFilterExposer.getMyTasksFilter(context.getResources()));
    } else {
      Filter savedFilter = createSearchFilter(searchQuery);
      taskListViewModel.searchByFilter(savedFilter);
    }
  }

  private Filter createSearchFilter(String query) {
    return new SearchFilter(getString(R.string.FLA_search_filter, query), query);
  }

  @Override
  public boolean onMenuItemClick(MenuItem item) {
    switch (item.getItemId()) {
      case R.id.menu_voice_add:
        Intent recognition = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        recognition.putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        recognition.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        recognition.putExtra(
            RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_create_prompt));
        startActivityForResult(recognition, TaskListFragment.VOICE_RECOGNITION_REQUEST_CODE);
        return true;
      case R.id.menu_sort:
        SortDialog.newSortDialog(filter.supportsManualSort())
            .show(getChildFragmentManager(), FRAG_TAG_SORT_DIALOG);
        return true;
      case R.id.menu_show_hidden:
        item.setChecked(!item.isChecked());
        preferences.setBoolean(R.string.p_show_hidden_tasks, item.isChecked());
        loadTaskListContent();
        localBroadcastManager.broadcastRefresh();
        return true;
      case R.id.menu_show_completed:
        item.setChecked(!item.isChecked());
        preferences.setBoolean(R.string.p_show_completed_tasks, item.isChecked());
        loadTaskListContent();
        localBroadcastManager.broadcastRefresh();
        return true;
      case R.id.menu_clear_completed:
        dialogBuilder
            .newDialog(R.string.clear_completed_tasks_confirmation)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> clearCompleted())
            .setNegativeButton(android.R.string.cancel, null)
            .show();
        return true;
      case R.id.menu_filter_settings:
        Intent filterSettings = new Intent(getActivity(), FilterSettingsActivity.class);
        filterSettings.putExtra(FilterSettingsActivity.TOKEN_FILTER, filter);
        startActivityForResult(filterSettings, REQUEST_LIST_SETTINGS);
        return true;
      case R.id.menu_caldav_list_fragment:
        CaldavCalendar calendar = ((CaldavFilter) filter).getCalendar();
        CaldavAccount account = caldavDao.getAccountByUuid(calendar.getAccount());
        Intent caldavSettings =
            new Intent(
                getActivity(),
                account.isCaldavAccount()
                    ? CaldavCalendarSettingsActivity.class
                    : EteSyncCalendarSettingsActivity.class);
        caldavSettings.putExtra(EXTRA_CALDAV_CALENDAR, calendar);
        startActivityForResult(caldavSettings, REQUEST_LIST_SETTINGS);
        return true;
      case R.id.menu_location_settings:
        Place place = ((PlaceFilter) filter).getPlace();
        Intent intent =
            new Intent(
                getActivity(),
                PlaceSettingsActivity.class);
        intent.putExtra(PlaceSettingsActivity.EXTRA_PLACE, (Parcelable) place);
        startActivityForResult(intent, REQUEST_LIST_SETTINGS);
        return true;
      case R.id.menu_gtasks_list_settings:
        Intent gtasksSettings = new Intent(getActivity(), GoogleTaskListSettingsActivity.class);
        gtasksSettings.putExtra(
            GoogleTaskListSettingsActivity.EXTRA_STORE_DATA, ((GtasksFilter) filter).getList());
        startActivityForResult(gtasksSettings, REQUEST_LIST_SETTINGS);
        return true;
      case R.id.menu_tag_settings:
        Intent tagSettings = new Intent(getActivity(), TagSettingsActivity.class);
        tagSettings.putExtra(TagSettingsActivity.EXTRA_TAG_DATA, ((TagFilter) filter).getTagData());
        startActivityForResult(tagSettings, REQUEST_LIST_SETTINGS);
        return true;
      case R.id.menu_expand_subtasks:
        taskDao.setCollapsed(taskListViewModel.getValue(), false);
        localBroadcastManager.broadcastRefresh();
        return true;
      case R.id.menu_collapse_subtasks:
        taskDao.setCollapsed(taskListViewModel.getValue(), true);
        localBroadcastManager.broadcastRefresh();
        return true;
      case R.id.menu_open_map:
        ((PlaceFilter) filter).openMap(context);
        return true;
      case R.id.menu_share:
        send(transform(taskDao.fetchTasks(preferences, filter), TaskContainer::getTask));
        return true;
      default:
        return onOptionsItemSelected(item);
    }
  }

  private void clearCompleted() {
    disposables.add(
        Single.fromCallable(() -> taskDeleter.clearCompleted(filter))
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                count -> toaster.longToast(R.string.delete_multiple_tasks_confirmation, count)));
  }

  @OnClick(R.id.fab)
  void createNewTask() {
    onTaskListItemClicked(addTask(""));
  }

  private Task addTask(String title) {
    return taskCreator.createWithValues(filter, title);
  }

  private void setupRefresh(SwipeRefreshLayout layout) {
    layout.setOnRefreshListener(this);
    layout.setColorSchemeColors(
        colorProvider.getPriorityColor(0, true),
        colorProvider.getPriorityColor(1, true),
        colorProvider.getPriorityColor(2, true),
        colorProvider.getPriorityColor(3, true));
  }

  @Override
  public void onResume() {
    super.onResume();

    disposables = new CompositeDisposable();

    localBroadcastManager.registerRefreshReceiver(refreshReceiver);

    refresh();
  }

  public Snackbar makeSnackbar(@StringRes int res, Object... args) {
    return makeSnackbar(getString(res, args));
  }

  public Snackbar makeSnackbar(String text) {
    Snackbar snackbar =
        Snackbar.make(coordinatorLayout, text, 8000)
            .setTextColor(getColor(context, R.color.snackbar_text_color))
            .setActionTextColor(getColor(context, R.color.snackbar_action_color));
    snackbar.getView().setBackgroundColor(getColor(context, R.color.snackbar_background));
    return snackbar;
  }

  @Override
  public void onPause() {
    super.onPause();

    disposables.dispose();

    localBroadcastManager.unregisterReceiver(refreshReceiver);
  }

  @Override
  public void onDestroyView() {
    super.onDestroyView();
    if (searchDisposable != null && !searchDisposable.isDisposed()) {
      searchDisposable.dispose();
    }
  }

  boolean collapseSearchView() {
    return search.isActionViewExpanded()
        && (search.collapseActionView() || !search.isActionViewExpanded());
  }

  private void refresh() {
    loadTaskListContent();

    setSyncOngoing();
  }

  public void loadTaskListContent() {
    taskListViewModel.invalidate();
  }

  public Filter getFilter() {
    return getArguments().getParcelable(EXTRA_FILTER);
  }

  private void onTaskCreated(List<Task> tasks) {
    for (Task task : tasks) {
      onTaskCreated(task.getUuid());
    }
    syncAdapters.sync();
    loadTaskListContent();
  }

  void onTaskCreated(String uuid) {
    taskAdapter.onTaskCreated(uuid);
  }

  private void onTaskDelete(Task task) {
    MainActivity activity = (MainActivity) getActivity();
    if (activity != null) {
      TaskEditFragment tef = activity.getTaskEditFragment();
      if (tef != null && task.getId() == tef.model.getId()) {
        tef.discard();
      }
    }
    timerPlugin.stopTimer(task);
    taskAdapter.onTaskDeleted(task);
    loadTaskListContent();
  }

  @Override
  public void onActivityResult(int requestCode, int resultCode, Intent data) {
    switch (requestCode) {
      case VOICE_RECOGNITION_REQUEST_CODE:
        if (resultCode == RESULT_OK) {
          List<String> match = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
          if (match != null && match.size() > 0 && match.get(0).length() > 0) {
            String recognizedSpeech = match.get(0);
            recognizedSpeech =
                recognizedSpeech.substring(0, 1).toUpperCase()
                    + recognizedSpeech.substring(1).toLowerCase();

            onTaskListItemClicked(addTask(recognizedSpeech));
          }
        }
        break;
      case REQUEST_MOVE_TASKS:
        if (resultCode == RESULT_OK) {
          taskMover.move(
              taskAdapter.getSelected(),
              data.getParcelableExtra(RemoteListPicker.EXTRA_SELECTED_FILTER));
          finishActionMode();
        }
        break;
      case REQUEST_LIST_SETTINGS:
        if (resultCode == Activity.RESULT_OK) {
          String action = data.getAction();
          if (ACTION_DELETED.equals(action)) {
            openFilter(null);
          } else if (ACTION_RELOAD.equals(action)) {
            openFilter(data.getParcelableExtra(MainActivity.OPEN_FILTER));
          }
        }
        break;
      case REQUEST_TAG_TASKS:
        if (resultCode == RESULT_OK) {
          List<Long> modified =
              tagDataDao.applyTags(
                  taskDao.fetch(
                      (ArrayList<Long>) data.getSerializableExtra(TagPickerActivity.EXTRA_TASKS)),
                  data.getParcelableArrayListExtra(TagPickerActivity.EXTRA_PARTIALLY_SELECTED),
                  data.getParcelableArrayListExtra(TagPickerActivity.EXTRA_SELECTED));
          taskDao.touch(modified);
          finishActionMode();
        }
        break;
      case REQUEST_DUE_DATE:
        if (resultCode == RESULT_OK) {
          long taskId = data.getLongExtra(DateTimePicker.EXTRA_TASK, 0L);
          Task task = taskDao.fetch(taskId);
          long dueDate = data.getLongExtra(DateTimePicker.EXTRA_TIMESTAMP, 0L);
          if (newDateTime(dueDate).isAfterNow()) {
            notificationManager.cancel(task.getId());
          }
          task.setDueDateAdjustingHideUntil(dueDate);
          taskDao.save(task);
        }
        break;
      default:
        super.onActivityResult(requestCode, resultCode, data);
    }
  }

  @Override
  public boolean onContextItemSelected(android.view.MenuItem item) {
    return onOptionsItemSelected(item);
  }

  public void onTaskListItemClicked(Task task) {
    callbacks.onTaskListItemClicked(task);
  }

  @Override
  public boolean onMenuItemActionExpand(MenuItem item) {
    searchDisposable =
        searchSubject
            .debounce(SEARCH_DEBOUNCE_TIMEOUT, TimeUnit.MILLISECONDS)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(this::searchByQuery);
    if (searchQuery == null) {
      searchByQuery("");
    }
    Menu menu = toolbar.getMenu();
    for (int i = 0; i < menu.size(); i++) {
      menu.getItem(i).setVisible(false);
    }
    return true;
  }

  @Override
  public boolean onMenuItemActionCollapse(MenuItem item) {
    taskListViewModel.searchByFilter(filter);
    searchDisposable.dispose();
    searchQuery = null;
    setupMenu();
    return true;
  }

  @Override
  public boolean onQueryTextSubmit(String query) {
    openFilter(createSearchFilter(query.trim()));
    search.collapseActionView();
    return true;
  }

  @Override
  public boolean onQueryTextChange(String query) {
    searchSubject.onNext(query);
    return true;
  }

  public void broadcastRefresh() {
    localBroadcastManager.broadcastRefresh();
  }

  @Override
  public boolean onCreateActionMode(ActionMode actionMode, Menu menu) {
    MenuInflater inflater = actionMode.getMenuInflater();
    inflater.inflate(R.menu.menu_multi_select, menu);
    themeColor.colorMenu(menu);
    return true;
  }

  @Override
  public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
    return false;
  }

  @Override
  public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
    ArrayList<Long> selected = taskAdapter.getSelected();
    switch (item.getItemId()) {
      case R.id.edit_tags:
        Pair<Set<String>, Set<String>> tags = tagDataDao.getTagSelections(selected);
        Intent intent = new Intent(context, TagPickerActivity.class);
        intent.putExtra(TagPickerActivity.EXTRA_TASKS, selected);
        intent.putParcelableArrayListExtra(
            TagPickerActivity.EXTRA_PARTIALLY_SELECTED,
            newArrayList(tagDataDao.getByUuid(tags.first)));
        intent.putParcelableArrayListExtra(
            TagPickerActivity.EXTRA_SELECTED, newArrayList(tagDataDao.getByUuid(tags.second)));
        startActivityForResult(intent, REQUEST_TAG_TASKS);
        return true;
      case R.id.move_tasks:
        Filter singleFilter = taskMover.getSingleFilter(selected);
        (singleFilter == null
                ? newRemoteListSupportPicker(this, REQUEST_MOVE_TASKS)
                : newRemoteListSupportPicker(singleFilter, this, REQUEST_MOVE_TASKS))
            .show(getParentFragmentManager(), FRAG_TAG_REMOTE_LIST_PICKER);
        return true;
      case R.id.menu_select_all:
        taskAdapter.setSelected(
            transform(taskDao.fetchTasks(preferences, filter), TaskContainer::getId));
        updateModeTitle();
        recyclerAdapter.notifyDataSetChanged();
        return true;
      case R.id.menu_share:
        send(taskDao.fetch(taskAdapter.getSelected()));
        return true;
      case R.id.delete:
        dialogBuilder
            .newDialog(R.string.delete_selected_tasks)
            .setPositiveButton(
                android.R.string.ok, (dialogInterface, i) -> deleteSelectedItems(selected))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
        return true;
      case R.id.copy_tasks:
        dialogBuilder
            .newDialog(R.string.copy_selected_tasks)
            .setPositiveButton(
                android.R.string.ok, ((dialogInterface, i) -> copySelectedItems(selected)))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
        return true;
      default:
        return false;
    }
  }

  private void send(List<Task> tasks) {
    Intent intent = new Intent(Intent.ACTION_SEND);
    String output =
        Joiner.on("\n")
            .join(
                transform(
                    tasks, t -> String.format("%s %s", t.isCompleted() ? "☑" : "☐", t.getTitle())));
    intent.putExtra(Intent.EXTRA_SUBJECT, filter.listingTitle);
    intent.putExtra(Intent.EXTRA_TEXT, output);
    intent.setType("text/plain");

    startActivity(Intent.createChooser(intent, null));

    finishActionMode();
  }

  @Override
  public void onDestroyActionMode(ActionMode mode) {
    this.mode = null;
    if (taskAdapter.getNumSelected() > 0) {
      taskAdapter.clearSelections();
      recyclerAdapter.notifyDataSetChanged();
    }
  }

  public void showDateTimePicker(TaskContainer task) {
    FragmentManager fragmentManager = getParentFragmentManager();
    if (fragmentManager.findFragmentByTag(FRAG_TAG_DATE_TIME_PICKER) == null) {
      DateTimePicker.Companion.newDateTimePicker(
              this,
              REQUEST_DUE_DATE,
              task.getId(),
              task.getDueDate(),
              preferences.getBoolean(R.string.p_auto_dismiss_datetime_list_screen, false))
          .show(fragmentManager, FRAG_TAG_DATE_TIME_PICKER);
    }
  }

  public interface TaskListFragmentCallbackHandler {
    void onTaskListItemClicked(Task task);

    void onNavigationIconClicked();
  }

  protected class RefreshReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
      refresh();
    }
  }

  public boolean isActionModeActive() {
    return mode != null;
  }

  public void startActionMode() {
    if (mode == null) {
      mode = ((AppCompatActivity) getActivity()).startSupportActionMode(this);
      updateModeTitle();
      if (taskAdapter.supportsParentingOrManualSort()) {
        Flags.set(Flags.TLFP_NO_INTERCEPT_TOUCH);
      }
    }
  }

  public void finishActionMode() {
    if (mode != null) {
      mode.finish();
    }
  }

  public void updateModeTitle() {
    if (mode != null) {
      int count = Math.max(1, taskAdapter.getNumSelected());
      mode.setTitle(Integer.toString(count));
    }
  }

  private void deleteSelectedItems(List<Long> tasks) {
    finishActionMode();
    List<Task> result = taskDeleter.markDeleted(tasks);
    for (Task task : result) {
      onTaskDelete(task);
    }
    makeSnackbar(R.string.delete_multiple_tasks_confirmation, Integer.toString(result.size()))
        .show();
  }

  private void copySelectedItems(List<Long> tasks) {
    finishActionMode();
    List<Task> duplicates = taskDuplicator.duplicate(tasks);
    onTaskCreated(duplicates);
    makeSnackbar(R.string.copy_multiple_tasks_confirmation, Integer.toString(duplicates.size()))
        .show();
  }
}
