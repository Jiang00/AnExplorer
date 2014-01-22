/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.dworks.apps.anexplorer.fragment;

import static dev.dworks.apps.anexplorer.DocumentsActivity.TAG;
import static dev.dworks.apps.anexplorer.DocumentsActivity.State.ACTION_CREATE;
import static dev.dworks.apps.anexplorer.DocumentsActivity.State.ACTION_GET_CONTENT;
import static dev.dworks.apps.anexplorer.DocumentsActivity.State.ACTION_MANAGE;
import static dev.dworks.apps.anexplorer.DocumentsActivity.State.MODE_GRID;
import static dev.dworks.apps.anexplorer.DocumentsActivity.State.MODE_LIST;
import static dev.dworks.apps.anexplorer.DocumentsActivity.State.MODE_UNKNOWN;
import static dev.dworks.apps.anexplorer.DocumentsActivity.State.SORT_ORDER_UNKNOWN;
import static dev.dworks.apps.anexplorer.model.DocumentInfo.getCursorInt;
import static dev.dworks.apps.anexplorer.model.DocumentInfo.getCursorLong;
import static dev.dworks.apps.anexplorer.model.DocumentInfo.getCursorString;

import java.io.File;
import java.util.ArrayList;

import android.app.AlertDialog;
import android.app.Fragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.app.LoaderManager.LoaderCallbacks;
import android.app.ProgressDialog;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.Loader;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.text.format.Time;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseLongArray;
import android.view.ActionMode;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AbsListView.MultiChoiceModeListener;
import android.widget.AbsListView.RecyclerListener;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.common.collect.Lists;

import dev.dworks.apps.anexplorer.DocumentsActivity;
import dev.dworks.apps.anexplorer.DocumentsActivity.State;
import dev.dworks.apps.anexplorer.DocumentsApplication;
import dev.dworks.apps.anexplorer.R;
import dev.dworks.apps.anexplorer.cursor.RootCursorWrapper;
import dev.dworks.apps.anexplorer.loader.DirectoryLoader;
import dev.dworks.apps.anexplorer.loader.RecentLoader;
import dev.dworks.apps.anexplorer.misc.AsyncTask;
import dev.dworks.apps.anexplorer.misc.CancellationSignal;
import dev.dworks.apps.anexplorer.misc.ContentProviderClientCompat;
import dev.dworks.apps.anexplorer.misc.IconUtils;
import dev.dworks.apps.anexplorer.misc.MimePredicate;
import dev.dworks.apps.anexplorer.misc.OperationCanceledException;
import dev.dworks.apps.anexplorer.misc.ProviderExecutor;
import dev.dworks.apps.anexplorer.misc.ProviderExecutor.Preemptable;
import dev.dworks.apps.anexplorer.misc.RootsCache;
import dev.dworks.apps.anexplorer.misc.ThumbnailCache;
import dev.dworks.apps.anexplorer.misc.Utils;
import dev.dworks.apps.anexplorer.misc.ViewCompat;
import dev.dworks.apps.anexplorer.model.DirectoryResult;
import dev.dworks.apps.anexplorer.model.DocumentInfo;
import dev.dworks.apps.anexplorer.model.DocumentsContract;
import dev.dworks.apps.anexplorer.model.DocumentsContract.Document;
import dev.dworks.apps.anexplorer.model.RootInfo;
import dev.dworks.apps.anexplorer.provider.AppsProvider;
import dev.dworks.apps.anexplorer.provider.RecentsProvider;
import dev.dworks.apps.anexplorer.provider.RecentsProvider.StateColumns;

/**
 * Display the documents inside a single directory.
 */
public class DirectoryFragment extends ListFragment {

	private View mEmptyView;
	private ListView mListView;
	private GridView mGridView;

	private AbsListView mCurrentView;

	public static final int TYPE_NORMAL = 1;
	public static final int TYPE_SEARCH = 2;
	public static final int TYPE_RECENT_OPEN = 3;

	public static final int ANIM_NONE = 1;
	public static final int ANIM_SIDE = 2;
	public static final int ANIM_DOWN = 3;
	public static final int ANIM_UP = 4;

	private int mType = TYPE_NORMAL;
	private String mStateKey;

	private int mLastMode = MODE_UNKNOWN;
	private int mLastSortOrder = SORT_ORDER_UNKNOWN;
	private boolean mLastShowSize = false;
	private boolean mLastShowFolderSize = false;
	private boolean mLastShowThumbnail = false;

	private boolean mHideGridTitles = false;

	private boolean mSvelteRecents;
	private Point mThumbSize;

	private DocumentsAdapter mAdapter;
	private LoaderCallbacks<DirectoryResult> mCallbacks;
	private SparseLongArray mSizes = new SparseLongArray();
	
	private static final String EXTRA_TYPE = "type";
	private static final String EXTRA_ROOT = "root";
	private static final String EXTRA_DOC = "doc";
	private static final String EXTRA_QUERY = "query";
	private static final String EXTRA_IGNORE_STATE = "ignoreState";

	private final int mLoaderId = 42;
	private RootInfo root;
	private DocumentInfo doc;

	public static void showNormal(FragmentManager fm, RootInfo root, DocumentInfo doc, int anim) {
		show(fm, TYPE_NORMAL, root, doc, null, anim);
	}

	public static void showSearch(FragmentManager fm, RootInfo root, String query, int anim) {
		show(fm, TYPE_SEARCH, root, null, query, anim);
	}

	public static void showRecentsOpen(FragmentManager fm, int anim) {
		show(fm, TYPE_RECENT_OPEN, null, null, null, anim);
	}

	private static void show(FragmentManager fm, int type, RootInfo root, DocumentInfo doc, String query, int anim) {
		final Bundle args = new Bundle();
		args.putInt(EXTRA_TYPE, type);
		args.putParcelable(EXTRA_ROOT, root);
		args.putParcelable(EXTRA_DOC, doc);
		args.putString(EXTRA_QUERY, query);

		final FragmentTransaction ft = fm.beginTransaction();
		switch (anim) {
		case ANIM_SIDE:
			args.putBoolean(EXTRA_IGNORE_STATE, true);
			break;
		case ANIM_DOWN:
			args.putBoolean(EXTRA_IGNORE_STATE, true);
			ft.setCustomAnimations(R.animator.dir_down, R.animator.dir_frozen);
			break;
		case ANIM_UP:
			ft.setCustomAnimations(R.animator.dir_frozen, R.animator.dir_up);
			break;
		}

		final DirectoryFragment fragment = new DirectoryFragment();
		fragment.setArguments(args);

		ft.replace(R.id.container_directory, fragment);
		ft.commitAllowingStateLoss();
	}

	private static String buildStateKey(RootInfo root, DocumentInfo doc) {
		final StringBuilder builder = new StringBuilder();
		builder.append(root != null ? root.authority : "null").append(';');
		builder.append(root != null ? root.rootId : "null").append(';');
		builder.append(doc != null ? doc.documentId : "null");
		return builder.toString();
	}

	public static DirectoryFragment get(FragmentManager fm) {
		// TODO: deal with multiple directories shown at once
		return (DirectoryFragment) fm.findFragmentById(R.id.container_directory);
	}

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		// final Context context = inflater.getContext();
		final View view = inflater.inflate(R.layout.fragment_directory, container, false);

		mEmptyView = view.findViewById(android.R.id.empty);

		mListView = (ListView) view.findViewById(R.id.list);
		mListView.setOnItemClickListener(mItemListener);
		mListView.setMultiChoiceModeListener(mMultiListener);
		mListView.setRecyclerListener(mRecycleListener);

		mGridView = (GridView) view.findViewById(R.id.grid);
		mGridView.setOnItemClickListener(mItemListener);
		mGridView.setMultiChoiceModeListener(mMultiListener);
		mGridView.setRecyclerListener(mRecycleListener);

		return view;
	}

	@Override
	public void onDestroyView() {
		super.onDestroyView();

		// Cancel any outstanding thumbnail requests
		final ViewGroup target = (mListView.getAdapter() != null) ? mListView : mGridView;
		final int count = target.getChildCount();
		for (int i = 0; i < count; i++) {
			final View view = target.getChildAt(i);
			mRecycleListener.onMovedToScrapHeap(view);
		}

		// Tear down any selection in progress
		mListView.setChoiceMode(AbsListView.CHOICE_MODE_NONE);
		mGridView.setChoiceMode(AbsListView.CHOICE_MODE_NONE);
	}

	@Override
	public void onActivityCreated(Bundle savedInstanceState) {
		super.onActivityCreated(savedInstanceState);

		final Context context = getActivity();
		final State state = getDisplayState(DirectoryFragment.this);

		root = getArguments().getParcelable(EXTRA_ROOT);
		doc = getArguments().getParcelable(EXTRA_DOC);

		mAdapter = new DocumentsAdapter();
		mType = getArguments().getInt(EXTRA_TYPE);
		mStateKey = buildStateKey(root, doc);

		if (mType == TYPE_RECENT_OPEN) {
			// Hide titles when showing recents for picking images/videos
			mHideGridTitles = MimePredicate.mimeMatches(MimePredicate.VISUAL_MIMES, state.acceptMimes);
		} else {
			mHideGridTitles = (doc != null) && doc.isGridTitlesHidden();
		}

		// final ActivityManager am = (ActivityManager)
		// context.getSystemService(Context.ACTIVITY_SERVICE);
		mSvelteRecents = !Utils.hasMoreHeap() && (mType == TYPE_RECENT_OPEN);

		mCallbacks = new LoaderCallbacks<DirectoryResult>() {
			@Override
			public Loader<DirectoryResult> onCreateLoader(int id, Bundle args) {
				final String query = getArguments().getString(EXTRA_QUERY);

				Uri contentsUri;
				switch (mType) {
				case TYPE_NORMAL:
					contentsUri = DocumentsContract.buildChildDocumentsUri(doc.authority, doc.documentId);
					if (state.action == ACTION_MANAGE) {
						contentsUri = DocumentsContract.setManageMode(contentsUri);
					}
					return new DirectoryLoader(context, mType, root, doc, contentsUri, state.userSortOrder);
				case TYPE_SEARCH:
					contentsUri = DocumentsContract.buildSearchDocumentsUri(root.authority, root.rootId, query);
					if (state.action == ACTION_MANAGE) {
						contentsUri = DocumentsContract.setManageMode(contentsUri);
					}
					return new DirectoryLoader(context, mType, root, doc, contentsUri, state.userSortOrder);
				case TYPE_RECENT_OPEN:
					final RootsCache roots = DocumentsApplication.getRootsCache(context);
					return new RecentLoader(context, roots, state);
				default:
					throw new IllegalStateException("Unknown type " + mType);
				}
			}

			@Override
			public void onLoadFinished(Loader<DirectoryResult> loader, DirectoryResult result) {
				if (!isAdded())
					return;

				mAdapter.swapResult(result);

				// Push latest state up to UI
				// TODO: if mode change was racing with us, don't overwrite it
				if (result.mode != MODE_UNKNOWN) {
					state.derivedMode = result.mode;
				}
				state.derivedSortOrder = result.sortOrder;
				((DocumentsActivity) context).onStateChanged();

				updateDisplayState();

				// When launched into empty recents, show drawer
				if (mType == TYPE_RECENT_OPEN && mAdapter.isEmpty() && !state.stackTouched) {
					((DocumentsActivity) context).setRootsDrawerOpen(true);
				}
		        if (isResumed()) {
		            setListShown(true);
		        } else {
		            setListShownNoAnimation(true);
		        }	
				// Restore any previous instance state
				final SparseArray<Parcelable> container = state.dirState.remove(mStateKey);
				if (container != null && !getArguments().getBoolean(EXTRA_IGNORE_STATE, false)) {
					getView().restoreHierarchyState(container);
				} else if (mLastSortOrder != state.derivedSortOrder) {
					mListView.smoothScrollToPosition(0);
					mGridView.smoothScrollToPosition(0);
				}

				mLastSortOrder = state.derivedSortOrder;
			}

			@Override
			public void onLoaderReset(Loader<DirectoryResult> loader) {
				mAdapter.swapResult(null);
			}
		};
		setListAdapter(mAdapter);
		setListShown(false);
		// Kick off loader at least once
		getLoaderManager().restartLoader(mLoaderId, null, mCallbacks);

		updateDisplayState();
	}

	@Override
	public void onStop() {
		super.onStop();

		// Remember last scroll location
		final SparseArray<Parcelable> container = new SparseArray<Parcelable>();
		getView().saveHierarchyState(container);
		final State state = getDisplayState(this);
		state.dirState.put(mStateKey, container);
	}

	@Override
	public void onResume() {
		super.onResume();
		updateDisplayState();
	}

	public void onUserSortOrderChanged() {
		// Sort order change always triggers reload; we'll trigger state change
		// on the flip side.
		getLoaderManager().restartLoader(mLoaderId, null, mCallbacks);
	}

	public void onUserModeChanged() {
		final ContentResolver resolver = getActivity().getContentResolver();
		final State state = getDisplayState(this);

		final RootInfo root = getArguments().getParcelable(EXTRA_ROOT);
		final DocumentInfo doc = getArguments().getParcelable(EXTRA_DOC);

		if (root != null && doc != null) {
			final Uri stateUri = RecentsProvider.buildState(root.authority, root.rootId, doc.documentId);
			final ContentValues values = new ContentValues();
			values.put(StateColumns.MODE, state.userMode);

			new AsyncTask<Void, Void, Void>() {
				@Override
				protected Void doInBackground(Void... params) {
					resolver.insert(stateUri, values);
					return null;
				}
			}.execute();
		}

		// Mode change is just visual change; no need to kick loader, and
		// deliver change event immediately.
		state.derivedMode = state.userMode;
		((DocumentsActivity) getActivity()).onStateChanged();

		updateDisplayState();
	}

	private void updateDisplayState() {
		final State state = getDisplayState(this);

		if (mLastMode == state.derivedMode 
				&& mLastShowSize == state.showSize
				&& mLastShowFolderSize == state.showFolderSize 
				&& mLastShowThumbnail == state.showThumbnail)
			return;
		mLastMode = state.derivedMode;
		mLastShowSize = state.showSize;
		mLastShowFolderSize = state.showFolderSize;
		mLastShowThumbnail = state.showThumbnail;
		
		mListView.setVisibility(state.derivedMode == MODE_LIST ? View.VISIBLE : View.GONE);
		mGridView.setVisibility(state.derivedMode == MODE_GRID ? View.VISIBLE : View.GONE);

		final int choiceMode;
		if (state.allowMultiple) {
			choiceMode = ListView.CHOICE_MODE_MULTIPLE_MODAL;
		} else {
			choiceMode = ListView.CHOICE_MODE_NONE;
		}

		final int thumbSize;
		if (state.derivedMode == MODE_GRID) {
			thumbSize = getResources().getDimensionPixelSize(R.dimen.grid_width);
			mListView.setAdapter(null);
			mListView.setChoiceMode(ListView.CHOICE_MODE_NONE);
			mGridView.setAdapter(mAdapter);
			mGridView.setColumnWidth(getResources().getDimensionPixelSize(R.dimen.grid_width));
			mGridView.setNumColumns(GridView.AUTO_FIT);
			mGridView.setChoiceMode(choiceMode);
			mCurrentView = mGridView;
		} else if (state.derivedMode == MODE_LIST) {
			thumbSize = getResources().getDimensionPixelSize(R.dimen.icon_size);
			mGridView.setAdapter(null);
			mGridView.setChoiceMode(ListView.CHOICE_MODE_NONE);
			mListView.setAdapter(mAdapter);
			mListView.setChoiceMode(choiceMode);
			mCurrentView = mListView;
		} else {
			throw new IllegalStateException("Unknown state " + state.derivedMode);
		}

		mThumbSize = new Point(thumbSize, thumbSize);
	}

	private OnItemClickListener mItemListener = new OnItemClickListener() {
		@Override
		public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
			final Cursor cursor = mAdapter.getItem(position);
			if (cursor != null) {
				final String docId = getCursorString(cursor, Document.COLUMN_DOCUMENT_ID);
				final String docMimeType = getCursorString(cursor, Document.COLUMN_MIME_TYPE);
				final int docFlags = getCursorInt(cursor, Document.COLUMN_FLAGS);
				if(null != root && root.isApp()){
					startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + AppsProvider.getPackageForDocId(docId))));			
				}
				else if (isDocumentEnabled(docMimeType, docFlags)) {
					final DocumentInfo doc = DocumentInfo.fromDirectoryCursor(cursor);
					((DocumentsActivity) getActivity()).onDocumentPicked(doc);
				}
			}
		}
	};

	private MultiChoiceModeListener mMultiListener = new MultiChoiceModeListener() {
		
		boolean selectAll = true;
		private boolean isApp;
		private boolean editMode;
		
		@Override
		public boolean onCreateActionMode(ActionMode mode, Menu menu) {
			editMode = root != null && root.isEditSupported();
			int menuId = R.menu.mode_simple_directory;
			if(null != root && root.isApp()){
				menuId = R.menu.mode_apps;				
			}
			else{
				if(editMode){
					menuId = R.menu.mode_directory;
				}
			}
			
			mode.getMenuInflater().inflate(menuId, menu);
			return true;
		}

		@Override
		public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
			final int count = mCurrentView.getCheckedItemCount();
			final State state = getDisplayState(DirectoryFragment.this);
			
			final MenuItem open = menu.findItem(R.id.menu_open);
			final MenuItem share = menu.findItem(R.id.menu_share);
			final MenuItem delete = menu.findItem(R.id.menu_delete);

			final boolean manageMode = state.action == ACTION_GET_CONTENT;
			final boolean canDelete = doc != null && doc.isDeleteSupported();
			isApp = root != null && root.isApp();
			open.setVisible(!manageMode);
			share.setVisible(manageMode);
			delete.setVisible(canDelete);
			if(isApp){
				share.setVisible(false);
				final MenuItem save = menu.findItem(R.id.menu_save);
				save.setVisible(root.isAppPackage());
				delete.setIcon(root.isAppProcess()? R.drawable.ic_menu_stop : R.drawable.ic_menu_delete);
				delete.setTitle(root.isAppProcess() ? "Stop" : "Uninstall");
			}
			else{
				if(editMode){
					
					final MenuItem info = menu.findItem(R.id.menu_info);
					final MenuItem copy = menu.findItem(R.id.menu_copy);
					final MenuItem cut = menu.findItem(R.id.menu_cut);
					//final MenuItem compress = menu.findItem(R.id.menu_compress);
					copy.setVisible(editMode);
					cut.setVisible(editMode);
					info.setVisible(count == 1);
				}
			}
			return true;
		}

		@Override
		public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
			final SparseBooleanArray checked = mCurrentView.getCheckedItemPositions();
			final ArrayList<DocumentInfo> docs = Lists.newArrayList();
			final int size = checked.size();
			for (int i = 0; i < size; i++) {
				if (checked.valueAt(i)) {
					final Cursor cursor = mAdapter.getItem(checked.keyAt(i));
					final DocumentInfo doc = DocumentInfo.fromDirectoryCursor(cursor);
					docs.add(doc);
				}
			}

			final int id = item.getItemId();
			switch (id) {
			case R.id.menu_open:
				DocumentsActivity.get(DirectoryFragment.this).onDocumentsPicked(docs);
				mode.finish();
				return true;

			case R.id.menu_share:
				onShareDocuments(docs);
				mode.finish();
				return true;
				
			case R.id.menu_copy:
				MoveFragment.show(getFragmentManager(), docs, false);
				mode.finish();
				return true;
				
			case R.id.menu_cut:
				MoveFragment.show(getFragmentManager(), docs, true);
				mode.finish();
				return true;
				
			case R.id.menu_delete:
				if(isApp && root.isAppPackage()){
					//TODO
				}
				else{
					deleteFiles(docs, id, isApp && root.isAppProcess() ? "Stop processes ?" : "Delete files ?");
				}
				mode.finish();
				return true;
				
			case R.id.menu_save:
				new OperationTask(docs, id).execute();
				mode.finish();
				return true;
				
			case R.id.menu_select_all:
				for (int i = 0; i < mAdapter.getCount(); i++) {
					mCurrentView.setItemChecked(i, selectAll);
				}
				selectAll = !selectAll;
				return true;
				
			case R.id.menu_info:
				((DocumentsActivity) getActivity()).setInfoDrawerOpen(true);
				mode.finish();
				return true;

			default:
				return false;
			}
		}

		@Override
		public void onDestroyActionMode(ActionMode mode) {
			// ignored
		}

		@Override
		public void onItemCheckedStateChanged(ActionMode mode, int position, long id, boolean checked) {
			if (checked) {
				// Directories and footer items cannot be checked
				boolean valid = false;

				final Cursor cursor = mAdapter.getItem(position);
				if (cursor != null) {
					final String docMimeType = getCursorString(cursor, Document.COLUMN_MIME_TYPE);
					final int docFlags = getCursorInt(cursor, Document.COLUMN_FLAGS);
					//if (!Document.MIME_TYPE_DIR.equals(docMimeType)) {
						valid = isDocumentEnabled(docMimeType, docFlags);
					//}
				}

				if (!valid) {
					mCurrentView.setItemChecked(position, false);
				}
			}

			int count = mCurrentView.getCheckedItemCount();
			mode.setTitle(getResources().getString(R.string.mode_selected_count, count));
			if(count == 1 || count == 2){
				mode.invalidate();
			}
		}
	};

	private RecyclerListener mRecycleListener = new RecyclerListener() {
		@Override
		public void onMovedToScrapHeap(View view) {
			final ImageView iconThumb = (ImageView) view.findViewById(R.id.icon_thumb);
			if (iconThumb != null) {
				final ThumbnailAsyncTask oldTask = (ThumbnailAsyncTask) iconThumb.getTag();
				if (oldTask != null) {
					oldTask.preempt();
					iconThumb.setTag(null);
				}
			}
		}
	};

	private void onShareDocuments(ArrayList<DocumentInfo> docs) {
		Intent intent;
		if (docs.size() == 1) {
			final DocumentInfo doc = docs.get(0);

			intent = new Intent(Intent.ACTION_SEND);
			intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
			// intent.addCategory(Intent.CATEGORY_DEFAULT);
			intent.setType(doc.mimeType);
			intent.putExtra(Intent.EXTRA_STREAM, doc.derivedUri);

		} else if (docs.size() > 1) {
			intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
			intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
			// intent.addCategory(Intent.CATEGORY_DEFAULT);

			final ArrayList<String> mimeTypes = Lists.newArrayList();
			final ArrayList<Uri> uris = Lists.newArrayList();
			for (DocumentInfo doc : docs) {
				mimeTypes.add(doc.mimeType);
				uris.add(doc.derivedUri);
			}

			intent.setType(findCommonMimeType(mimeTypes));
			intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);

		} else {
			return;
		}

		intent = Intent.createChooser(intent, getActivity().getText(R.string.share_via));
		startActivity(intent);
	}

	private boolean onDeleteDocuments(ArrayList<DocumentInfo> docs) {
		final Context context = getActivity();
		final ContentResolver resolver = context.getContentResolver();

		boolean hadTrouble = false;
		for (DocumentInfo doc : docs) {
			if (!doc.isDeleteSupported()) {
				Log.w(TAG, "Skipping " + doc);
				hadTrouble = true;
				continue;
			}

			try {
				DocumentsContract.deleteDocument(resolver, doc.derivedUri);
			} catch (Exception e) {
				Log.w(TAG, "Failed to delete " + doc);
				hadTrouble = true;
			}
		}
		
		return hadTrouble;
	}
	
	private class OperationTask extends AsyncTask<Void, Void, Boolean>{

		private ProgressDialog progressDialog;
		private ArrayList<DocumentInfo> docs;
		private int id;

		public OperationTask(ArrayList<DocumentInfo> docs, int id) {
			this.docs = docs;
			this.id = id;
			progressDialog = new ProgressDialog(getActivity());
			progressDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
			//progressDialog.setIndeterminate(true);
			progressDialog.setCancelable(false);
			
			switch (id) {
			case R.id.menu_delete:
				if(null != root && root.isApp()){
					progressDialog.setMessage("Stopping processes...");
				}
				else{
					progressDialog.setMessage("Deleting files...");
				}
				break;

			case R.id.menu_save:
				progressDialog.setMessage("Saving apps...");
				break;
			default:
				break;
			}
		}
		
		@Override
		protected void onPreExecute() {
			progressDialog.show();
			super.onPreExecute();
		}
		
		@Override
		protected Boolean doInBackground(Void... params) {
			boolean result = false;
			switch (id) {
			case R.id.menu_delete:
				result = onDeleteDocuments(docs);
				break;

			case R.id.menu_save:
				result = onSaveDocuments(docs);
				break;
			}
			
			return result;
		}
		
		@Override
		protected void onPostExecute(Boolean result) {
			super.onPostExecute(result);
			
			progressDialog.dismiss();
			if (result) {
				switch (id) {
				case R.id.menu_delete:
					Toast.makeText(getActivity(), R.string.toast_failed_delete, Toast.LENGTH_SHORT).show();
					break;

				case R.id.menu_save:
					Toast.makeText(getActivity(), R.string.toast_failed_delete, Toast.LENGTH_SHORT).show();
					break;
				}

			}
			if(null != root && root.isAppProcess()){
				AppsProvider.notifyDocumentsChanged(getActivity(), root.rootId);
				AppsProvider.notifyRootsChanged(getActivity());
			}
			if(!(null != root && root.isAppPackage())){
				((DocumentsActivity)getActivity()).onCurrentDirectoryChanged(ANIM_NONE);	
			}
		}
	}
	
	private void deleteFiles(final ArrayList<DocumentInfo> docs, final int id, String title) {
		AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
		builder.setMessage(title)
		.setCancelable(false)
		.setPositiveButton("Ok", new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int did) {
				dialog.dismiss();
				new OperationTask(docs, id).execute();
			}
		})
		.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int did) {
				dialog.dismiss();
			}
		});
		builder.create().show();
	}
	
	private static State getDisplayState(Fragment fragment) {
		return ((DocumentsActivity) fragment.getActivity()).getDisplayState();
	}

	public boolean onSaveDocuments(ArrayList<DocumentInfo> docs) {
		final Context context = getActivity();
		final ContentResolver resolver = context.getContentResolver();

		boolean hadTrouble = false;
		for (DocumentInfo doc : docs) {
			if (!doc.isEditSupported()) {
				Log.w(TAG, "Skipping " + doc);
				hadTrouble = true;
				continue;
			}

			try {
				DocumentsContract.moveDocument(resolver, doc.derivedUri, null, false);
			} catch (Exception e) {
				Log.w(TAG, "Failed to save " + doc);
				hadTrouble = true;
			}
		}
		
		return hadTrouble;
	}

	private static abstract class Footer {
		private final int mItemViewType;

		public Footer(int itemViewType) {
			mItemViewType = itemViewType;
		}

		public abstract View getView(View convertView, ViewGroup parent);

		public int getItemViewType() {
			return mItemViewType;
		}
	}

	private class LoadingFooter extends Footer {
		public LoadingFooter() {
			super(1);
		}

		@Override
		public View getView(View convertView, ViewGroup parent) {
			final Context context = parent.getContext();
			final State state = getDisplayState(DirectoryFragment.this);

			if (convertView == null) {
				final LayoutInflater inflater = LayoutInflater.from(context);
				if (state.derivedMode == MODE_LIST) {
					convertView = inflater.inflate(R.layout.item_loading_list, parent, false);
				} else if (state.derivedMode == MODE_GRID) {
					convertView = inflater.inflate(R.layout.item_loading_grid, parent, false);
				} else {
					throw new IllegalStateException();
				}
			}

			return convertView;
		}
	}

	private class MessageFooter extends Footer {
		private final int mIcon;
		private final String mMessage;

		public MessageFooter(int itemViewType, int icon, String message) {
			super(itemViewType);
			mIcon = icon;
			mMessage = message;
		}

		@Override
		public View getView(View convertView, ViewGroup parent) {
			final Context context = parent.getContext();
			final State state = getDisplayState(DirectoryFragment.this);

			if (convertView == null) {
				final LayoutInflater inflater = LayoutInflater.from(context);
				if (state.derivedMode == MODE_LIST) {
					convertView = inflater.inflate(R.layout.item_message_list, parent, false);
				} else if (state.derivedMode == MODE_GRID) {
					convertView = inflater.inflate(R.layout.item_message_grid, parent, false);
				} else {
					throw new IllegalStateException();
				}
			}

			final ImageView icon = (ImageView) convertView.findViewById(android.R.id.icon);
			final TextView title = (TextView) convertView.findViewById(android.R.id.title);
			icon.setImageResource(mIcon);
			title.setText(mMessage);
			return convertView;
		}
	}

	private class DocumentsAdapter extends BaseAdapter implements OnClickListener {
		private Cursor mCursor;
		private int mCursorCount;

		private ArrayList<Footer> mFooters = Lists.newArrayList();

		public void swapResult(DirectoryResult result) {
			mCursor = result != null ? result.cursor : null;
			mCursorCount = mCursor != null ? mCursor.getCount() : 0;
			
			mSizes.clear();
			mFooters.clear();

			final Bundle extras = mCursor != null ? mCursor.getExtras() : null;
			if (extras != null) {
				final String info = extras.getString(DocumentsContract.EXTRA_INFO);
				if (info != null) {
					mFooters.add(new MessageFooter(2, R.drawable.ic_dialog_info, info));
				}
				final String error = extras.getString(DocumentsContract.EXTRA_ERROR);
				if (error != null) {
					mFooters.add(new MessageFooter(3, R.drawable.ic_dialog_alert, error));
				}
				if (extras.getBoolean(DocumentsContract.EXTRA_LOADING, false)) {
					mFooters.add(new LoadingFooter());
				}
			}

			if (result != null && result.exception != null) {
				mFooters.add(new MessageFooter(3, R.drawable.ic_dialog_alert, getString(R.string.query_error)));
			}

			if (isEmpty()) {
				mEmptyView.setVisibility(View.VISIBLE);
			} else {
				mEmptyView.setVisibility(View.GONE);
			}

			notifyDataSetChanged();
		}

		@Override
		public View getView(int position, View convertView, ViewGroup parent) {
			if (position < mCursorCount) {
				return getDocumentView(position, convertView, parent);
			} else {
				position -= mCursorCount;
				convertView = mFooters.get(position).getView(convertView, parent);
				// Only the view itself is disabled; contents inside shouldn't
				// be dimmed.
				convertView.setEnabled(false);
				return convertView;
			}
		}

		private View getDocumentView(int position, View convertView, ViewGroup parent) {
			final Context context = parent.getContext();
			final State state = getDisplayState(DirectoryFragment.this);

			// final DocumentInfo doc = getArguments().getParcelable(EXTRA_DOC);

			final RootsCache roots = DocumentsApplication.getRootsCache(context);
			final ThumbnailCache thumbs = DocumentsApplication.getThumbnailsCache(context, mThumbSize);

			if (convertView == null) {
				final LayoutInflater inflater = LayoutInflater.from(context);
				if (state.derivedMode == MODE_LIST) {
					convertView = inflater.inflate(R.layout.item_doc_list, parent, false);
				} else if (state.derivedMode == MODE_GRID) {
					convertView = inflater.inflate(R.layout.item_doc_grid, parent, false);

					// Apply padding to grid items
					final FrameLayout grid = (FrameLayout) convertView;
					final int gridPadding = getResources().getDimensionPixelSize(R.dimen.grid_padding);

					// Tricksy hobbitses! We need to fully clear the drawable so
					// the view doesn't clobber the new InsetDrawable callback
					// when setting back later.
					final Drawable fg = grid.getForeground();
					final Drawable bg = grid.getBackground();
					grid.setForeground(null);
					// grid.setBackground(null);
					ViewCompat.setBackground(grid, null);
					grid.setForeground(new InsetDrawable(fg, gridPadding));
					// grid.setBackground(new InsetDrawable(bg, gridPadding));
					ViewCompat.setBackground(grid, new InsetDrawable(bg, gridPadding));
				} else {
					throw new IllegalStateException();
				}
			}

			final Cursor cursor = getItem(position);

			final String docAuthority = getCursorString(cursor, RootCursorWrapper.COLUMN_AUTHORITY);
			final String docRootId = getCursorString(cursor, RootCursorWrapper.COLUMN_ROOT_ID);
			final String docId = getCursorString(cursor, Document.COLUMN_DOCUMENT_ID);
			final String docMimeType = getCursorString(cursor, Document.COLUMN_MIME_TYPE);
			final String docPath = getCursorString(cursor, Document.COLUMN_PATH);
			final String docDisplayName = getCursorString(cursor, Document.COLUMN_DISPLAY_NAME);
			final long docLastModified = getCursorLong(cursor, Document.COLUMN_LAST_MODIFIED);
			final int docIcon = getCursorInt(cursor, Document.COLUMN_ICON);
			final int docFlags = getCursorInt(cursor, Document.COLUMN_FLAGS);
			final String docSummary = getCursorString(cursor, Document.COLUMN_SUMMARY);
			final long docSize = getCursorLong(cursor, Document.COLUMN_SIZE);

			final View line1 = convertView.findViewById(R.id.line1);
			final View line2 = convertView.findViewById(R.id.line2);

			final ImageView iconMime = (ImageView) convertView.findViewById(R.id.icon_mime);
			final ImageView iconThumb = (ImageView) convertView.findViewById(R.id.icon_thumb);
			final TextView title = (TextView) convertView.findViewById(android.R.id.title);
			final ImageView icon1 = (ImageView) convertView.findViewById(android.R.id.icon1);
			final ImageView icon2 = (ImageView) convertView.findViewById(android.R.id.icon2);
			final TextView summary = (TextView) convertView.findViewById(android.R.id.summary);
			final TextView date = (TextView) convertView.findViewById(R.id.date);
			final TextView size = (TextView) convertView.findViewById(R.id.size);

			final View iconView = convertView.findViewById(android.R.id.icon);
			if(null != iconView){
				iconView.setOnClickListener(this);
			}
			final ThumbnailAsyncTask oldTask = (ThumbnailAsyncTask) iconThumb.getTag();
			if (oldTask != null) {
				oldTask.preempt();
				iconThumb.setTag(null);
			}

			iconMime.animate().cancel();
			iconThumb.animate().cancel();

			final boolean supportsThumbnail = (docFlags & Document.FLAG_SUPPORTS_THUMBNAIL) != 0;
			final boolean allowThumbnail = (state.derivedMode == MODE_GRID) || MimePredicate.mimeMatches(MimePredicate.VISUAL_MIMES, docMimeType);
			final boolean showThumbnail = supportsThumbnail && allowThumbnail && !mSvelteRecents && state.showThumbnail;

			boolean cacheHit = false;
			if (showThumbnail) {
				final Uri uri = DocumentsContract.buildDocumentUri(docAuthority, docId);
				final Bitmap cachedResult = thumbs.get(uri);
				if (cachedResult != null) {
					iconThumb.setScaleType(!TextUtils.isEmpty(docPath) ? ImageView.ScaleType.CENTER_INSIDE : ImageView.ScaleType.CENTER_CROP);
					iconThumb.setImageBitmap(cachedResult);
					cacheHit = true;
				} else {
					iconThumb.setImageDrawable(null);
					final ThumbnailAsyncTask task = new ThumbnailAsyncTask(uri, iconMime, iconThumb, mThumbSize,
							docMimeType.equals(Document.MIME_TYPE_APK) ? docPath : null);
					iconThumb.setTag(task);
					ProviderExecutor.forAuthority(docAuthority).execute(task);
				}
			}

			// Always throw MIME icon into place, even when a thumbnail is being
			// loaded in background.
			if (cacheHit) {
				iconMime.setAlpha(0f);
				iconMime.setImageDrawable(null);
				iconThumb.setAlpha(1f);
			} else {
				iconMime.setAlpha(1f);
				iconThumb.setAlpha(0f);
				iconThumb.setImageDrawable(null);
				if (docIcon != 0) {
					iconMime.setImageDrawable(IconUtils.loadPackageIcon(context, docAuthority, docIcon));
				} else {
					iconMime.setImageDrawable(IconUtils.loadMimeIcon(context, docMimeType, docAuthority, docId, state.derivedMode));
				}
			}

			boolean hasLine1 = false;
			boolean hasLine2 = false;

			final boolean hideTitle = (state.derivedMode == MODE_GRID) && mHideGridTitles;
			if (!hideTitle) {
				title.setText(docDisplayName);
				hasLine1 = true;
			}

			Drawable iconDrawable = null;
			if (mType == TYPE_RECENT_OPEN) {
				// We've already had to enumerate roots before any results can
				// be shown, so this will never block.
				final RootInfo root = roots.getRootBlocking(docAuthority, docRootId);
				iconDrawable = root.loadIcon(context);

				if (summary != null) {
					final boolean alwaysShowSummary = getResources().getBoolean(R.bool.always_show_summary);
					if (alwaysShowSummary) {
						summary.setText(root.getDirectoryString());
						summary.setVisibility(View.VISIBLE);
						hasLine2 = true;
					} else {
						if (iconDrawable != null && roots.isIconUniqueBlocking(root)) {
							// No summary needed if icon speaks for itself
							summary.setVisibility(View.INVISIBLE);
						} else {
							summary.setText(root.getDirectoryString());
							summary.setVisibility(View.VISIBLE);
							// summary.setTextAlignment(TextView.TEXT_ALIGNMENT_TEXT_END);
							hasLine2 = true;
						}
					}
				}
			} else {
				// Directories showing thumbnails in grid mode get a little icon
				// hint to remind user they're a directory.
				if (Document.MIME_TYPE_DIR.equals(docMimeType) && state.derivedMode == MODE_GRID && showThumbnail) {
					iconDrawable = context.getResources().getDrawable(R.drawable.ic_root_folder);
				}

				if (summary != null) {
					if (docSummary != null) {
						summary.setText(docSummary);
						summary.setVisibility(View.VISIBLE);
						hasLine2 = true;
					} else {
						summary.setVisibility(View.INVISIBLE);
					}
				}
			}

			if (icon1 != null)
				icon1.setVisibility(View.GONE);
			if (icon2 != null)
				icon2.setVisibility(View.GONE);

			if (iconDrawable != null) {
				if (hasLine1) {
					icon1.setVisibility(View.VISIBLE);
					icon1.setImageDrawable(iconDrawable);
				} else {
					icon2.setVisibility(View.VISIBLE);
					icon2.setImageDrawable(iconDrawable);
				}
			}

			if (docLastModified == -1) {
				date.setText(null);
			} else {
				date.setText(formatTime(context, docLastModified));
				hasLine2 = true;
			}

			final FolderSizeAsyncTask oldSizeTask = (FolderSizeAsyncTask) size.getTag();
			if (oldSizeTask != null) {
				oldSizeTask.preempt();
				size.setTag(null);
			}
			if (state.showSize) {
				size.setVisibility(View.VISIBLE);
				if (Document.MIME_TYPE_DIR.equals(docMimeType) || docSize == -1) {
					if(state.showFolderSize){
						long sizeInBytes = mSizes.get(position, -1);
						if(sizeInBytes != -1){
							size.setText(Formatter.formatFileSize(context, sizeInBytes));
						}
						else{
							final FolderSizeAsyncTask task = new FolderSizeAsyncTask(size, docPath, position);
							size.setTag(task);
							ProviderExecutor.forAuthority(docAuthority).execute(task);
							size.setText(null);
						}
					}
				} else {
					size.setText(Formatter.formatFileSize(context, docSize));
					hasLine2 = true;
				}
			} else {
				size.setVisibility(View.GONE);
			}

			if (line1 != null) {
				line1.setVisibility(hasLine1 ? View.VISIBLE : View.GONE);
			}
			if (line2 != null) {
				line2.setVisibility(hasLine2 ? View.VISIBLE : View.GONE);
			}

			final boolean enabled = isDocumentEnabled(docMimeType, docFlags);
			if (enabled) {
				setEnabledRecursive(convertView, true);
				iconMime.setAlpha(1f);
				iconThumb.setAlpha(1f);
				if (icon1 != null)
					icon1.setAlpha(1f);
				if (icon2 != null)
					icon2.setAlpha(1f);
			} else {
				setEnabledRecursive(convertView, false);
				iconMime.setAlpha(0.5f);
				iconThumb.setAlpha(0.5f);
				if (icon1 != null)
					icon1.setAlpha(0.5f);
				if (icon2 != null)
					icon2.setAlpha(0.5f);
			}

			return convertView;
		}

		@Override
		public int getCount() {
			return mCursorCount + mFooters.size();
		}

		@Override
		public Cursor getItem(int position) {
			if (position < mCursorCount) {
				mCursor.moveToPosition(position);
				return mCursor;
			} else {
				return null;
			}
		}

		@Override
		public long getItemId(int position) {
			return position;
		}

		@Override
		public int getViewTypeCount() {
			return 4;
		}

		@Override
		public int getItemViewType(int position) {
			if (position < mCursorCount) {
				return 0;
			} else {
				position -= mCursorCount;
				return mFooters.get(position).getItemViewType();
			}
		}

		@Override
		public void onClick(View v) {
			int count = mCurrentView.getCheckedItemCount();
			if(count != 0){
				return;
			}
			final int position = mCurrentView.getPositionForView(v);
			if (position != ListView.INVALID_POSITION) {
	            ActionMode mChoiceActionMode = null;
				if (mChoiceActionMode == null &&
	                    (mChoiceActionMode = mCurrentView.startActionMode(mMultiListener)) != null) {
	                mCurrentView.setItemChecked(position, true);
	                mCurrentView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
	            }				
			}
		}
	}

	private static class ThumbnailAsyncTask extends AsyncTask<Uri, Void, Bitmap> implements Preemptable {
		private final Uri mUri;
		private final ImageView mIconMime;
		private final ImageView mIconThumb;
		private final Point mThumbSize;
		private final CancellationSignal mSignal;
		private final String mPath;

		public ThumbnailAsyncTask(Uri uri, ImageView iconMime, ImageView iconThumb, Point thumbSize, String path) {
			mUri = uri;
			mIconMime = iconMime;
			mIconThumb = iconThumb;
			mThumbSize = thumbSize;
			mSignal = new CancellationSignal();
			mPath = path;
		}

		@Override
		public void preempt() {
			cancel(false);
			mSignal.cancel();
		}

		@Override
		protected Bitmap doInBackground(Uri... params) {
			if (isCancelled())
				return null;

			final Context context = mIconThumb.getContext();
			final ContentResolver resolver = context.getContentResolver();

			ContentProviderClient client = null;
			Bitmap result = null;
			try {
				if (!TextUtils.isEmpty(mPath)) {
					result = ((BitmapDrawable) IconUtils.loadPackagePathIcon(context, mPath, Document.MIME_TYPE_APK)).getBitmap();
				} else {
					client = DocumentsApplication.acquireUnstableProviderOrThrow(resolver, mUri.getAuthority());
					result = DocumentsContract.getDocumentThumbnail(resolver, mUri, mThumbSize, mSignal);
				}
				if (result != null) {
					final ThumbnailCache thumbs = DocumentsApplication.getThumbnailsCache(context, mThumbSize);
					thumbs.put(mUri, result);
				}
			} catch (Exception e) {
				if (!(e instanceof OperationCanceledException)) {
					Log.w(TAG, "Failed to load thumbnail for " + mUri + ": " + e);
				}
			} finally {
				ContentProviderClientCompat.releaseQuietly(client);
			}
			return result;
		}

		@Override
		protected void onPostExecute(Bitmap result) {
			if (mIconThumb.getTag() == this && result != null) {
				mIconThumb.setScaleType(!TextUtils.isEmpty(mPath) ? ImageView.ScaleType.CENTER_INSIDE : ImageView.ScaleType.CENTER_CROP);
				mIconThumb.setTag(null);
				mIconThumb.setImageBitmap(result);

				final float targetAlpha = mIconMime.isEnabled() ? 1f : 0.5f;
				mIconMime.setAlpha(targetAlpha);
				mIconMime.animate().alpha(0f).start();
				mIconThumb.setAlpha(0f);
				mIconThumb.animate().alpha(targetAlpha).start();
			}
		}
	}

	private class FolderSizeAsyncTask extends AsyncTask<Uri, Void, Long> implements Preemptable {
		private final TextView mSizeView;
		private final CancellationSignal mSignal;
		private final String mPath;
		private final int mPosition;

		public FolderSizeAsyncTask(TextView sizeView, String path, int position) {
			mSizeView = sizeView;
			mSignal = new CancellationSignal();
			mPath = path;
			mPosition = position;
		}

		@Override
		public void preempt() {
			cancel(false);
			mSignal.cancel();
		}

		@Override
		protected Long doInBackground(Uri... params) {
			if (isCancelled())
				return null;

			//final Context context = mSizeView.getContext();
			Long result = null;
			try {
				if(!TextUtils.isEmpty(mPath)){
					File dir = new File(mPath);
					//result = Formatter.formatFileSize(context, getDirectorySize(dir));
					result = getDirectorySize(dir);
				}
			} catch (Exception e) {
				if (!(e instanceof OperationCanceledException)) {
					Log.w(TAG, "Failed to calculate size for " + mPath + ": " + e);
				}
			}
			return result;
		}

		@Override
		protected void onPostExecute(Long result) {
			if (mSizeView.getTag() == this && result != null) {
				mSizeView.setTag(null);
				String size = Formatter.formatFileSize(mSizeView.getContext(), result);
				mSizeView.setText(size);
				mSizes.put(mPosition, result);
			}
		}
	}

	private static String formatTime(Context context, long when) {
		// TODO: DateUtils should make this easier
		Time then = new Time();
		then.set(when);
		Time now = new Time();
		now.setToNow();

		int flags = DateUtils.FORMAT_NO_NOON | DateUtils.FORMAT_NO_MIDNIGHT | DateUtils.FORMAT_ABBREV_ALL;

		if (then.year != now.year) {
			flags |= DateUtils.FORMAT_SHOW_YEAR | DateUtils.FORMAT_SHOW_DATE;
		} else if (then.yearDay != now.yearDay) {
			flags |= DateUtils.FORMAT_SHOW_DATE;
		} else {
			flags |= DateUtils.FORMAT_SHOW_TIME;
		}

		return DateUtils.formatDateTime(context, when, flags);
	}

	private String findCommonMimeType(ArrayList<String> mimeTypes) {
		String[] commonType = mimeTypes.get(0).split("/");
		if (commonType.length != 2) {
			return "*/*";
		}

		for (int i = 1; i < mimeTypes.size(); i++) {
			String[] type = mimeTypes.get(i).split("/");
			if (type.length != 2)
				continue;

			if (!commonType[1].equals(type[1])) {
				commonType[1] = "*";
			}

			if (!commonType[0].equals(type[0])) {
				commonType[0] = "*";
				commonType[1] = "*";
				break;
			}
		}

		return commonType[0] + "/" + commonType[1];
	}

	private void setEnabledRecursive(View v, boolean enabled) {
		if (v == null)
			return;
		if (v.isEnabled() == enabled)
			return;
		v.setEnabled(enabled);

		if (v instanceof ViewGroup) {
			final ViewGroup vg = (ViewGroup) v;
			for (int i = vg.getChildCount() - 1; i >= 0; i--) {
				setEnabledRecursive(vg.getChildAt(i), enabled);
			}
		}
	}

	private boolean isDocumentEnabled(String docMimeType, int docFlags) {
		final State state = getDisplayState(DirectoryFragment.this);

		if (Document.MIME_TYPE_HIDDEN.equals(docMimeType)) {
			return false;
		}
		// Directories are always enabled
		if (Document.MIME_TYPE_DIR.equals(docMimeType)) {
			//return true;
		}

		// Read-only files are disabled when creating
		if (state.action == ACTION_CREATE && (docFlags & Document.FLAG_SUPPORTS_WRITE) == 0) {
			return false;
		}

		return MimePredicate.mimeMatches(state.acceptMimes, docMimeType);
	}

	private static long getDirectorySize(File dir) {
		long result = 0L;
		if (dir.listFiles() != null && dir.listFiles().length > 0) {
			for (File eachFile : dir.listFiles()) {
				result += eachFile.isDirectory() && eachFile.canRead() ? getDirectorySize(eachFile) : eachFile.length();
			}
		} else if (!dir.isDirectory()) {
			result = dir.length();
		}
		return result;
	}
}