package net.osmand.plus.download;

import java.io.File;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import android.graphics.Color;
import android.text.method.LinkMovementMethod;
import com.actionbarsherlock.app.ActionBar;
import com.actionbarsherlock.view.*;
import net.osmand.IProgress;
import net.osmand.IndexConstants;
import net.osmand.access.AccessibleToast;
import net.osmand.plus.ContextMenuAdapter;
import net.osmand.plus.ContextMenuAdapter.OnContextMenuClick;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.OsmandPlugin;
import net.osmand.plus.R;
import net.osmand.plus.activities.*;
import net.osmand.plus.activities.LocalIndexHelper.LocalIndexType;
import net.osmand.plus.helpers.FileNameTranslationHelper;
import net.osmand.util.Algorithms;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.AlertDialog.Builder;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.TypedArray;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.StatFs;
import android.text.SpannableString;
import android.text.TextPaint;
import android.text.style.ClickableSpan;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ExpandableListView;
import android.widget.ExpandableListView.ExpandableListContextMenuInfo;
import android.widget.TextView;
import android.widget.Toast;

import com.actionbarsherlock.view.ActionMode.Callback;

public class LocalIndexesFragment extends OsmandExpandableListFragment {

	private LoadLocalIndexTask asyncLoader;
	private LocalIndexesAdapter listAdapter;
	private LoadLocalIndexDescriptionTask descriptionLoader;
	private AsyncTask<LocalIndexInfo, ?, ?> operationTask;

	private boolean selectionMode = false;
	private Set<LocalIndexInfo> selectedItems = new LinkedHashSet<LocalIndexInfo>();
	
	protected static int DELETE_OPERATION = 1;
	protected static int BACKUP_OPERATION = 2;
	protected static int RESTORE_OPERATION = 3;
	
	MessageFormat formatMb = new MessageFormat("{0, number,##.#} MB", Locale.US);
	MessageFormat formatGb = new MessageFormat("{0, number,#.##} GB", Locale.US);
	private ContextMenuAdapter optionsMenuAdapter;
	private ActionMode actionMode;

	private TextView descriptionText;

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		View view = inflater.inflate(R.layout.local_index, container, false);

		getDownloadActivity().getSupportActionBar().setTitle(R.string.local_index_descr_title);
		getDownloadActivity().setSupportProgressBarIndeterminateVisibility(false);

		ExpandableListView listView = (ExpandableListView)view.findViewById(android.R.id.list);
		listAdapter = new LocalIndexesAdapter(getActivity());
		listView.setAdapter(listAdapter);
		setListView(listView);
		//getDownloadActivity().getSupportActionBar().setLogo(R.drawable.tab_download_screen_icon);
		descriptionText = (TextView) view.findViewById(R.id.DescriptionText);
		updateDescriptionTextWithSize();
		return view;
	}

	@SuppressWarnings("unchecked")
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		descriptionLoader = new LoadLocalIndexDescriptionTask();

		if (asyncLoader == null || asyncLoader.getResult() == null) {
			// getLastNonConfigurationInstance method should be in onCreate() method
			// (onResume() doesn't work)
			Object indexes = getActivity().getLastNonConfigurationInstance();
			asyncLoader = new LoadLocalIndexTask();
			if (indexes instanceof List<?>) {
				asyncLoader.setResult((List<LocalIndexInfo>) indexes);
			}
		}
		setHasOptionsMenu(true);
	}
	
	@Override
	public void onResume() {
		super.onResume();
		if (asyncLoader == null || asyncLoader.getResult() == null) {
			asyncLoader = new LoadLocalIndexTask();
			asyncLoader.execute(getActivity());
		}

		getExpandableListView().setOnCreateContextMenuListener(new View.OnCreateContextMenuListener() {
			@Override
			public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
				long packedPos = ((ExpandableListContextMenuInfo) menuInfo).packedPosition;
				int group = ExpandableListView.getPackedPositionGroup(packedPos);
				int child = ExpandableListView.getPackedPositionChild(packedPos);
				if (child >= 0 && group >= 0) {
					final LocalIndexInfo point = (LocalIndexInfo) listAdapter.getChild(group, child);
					showContextMenu(point);
				}
			}
		});
	}
	
	
	private void showContextMenu(final LocalIndexInfo info) {
		Builder builder = new AlertDialog.Builder(getActivity());
		final ContextMenuAdapter adapter = new ContextMenuAdapter(getActivity());
		basicFileOperation(info, adapter);
		OsmandPlugin.onContextMenuActivity(getActivity(), null, info, adapter);

		String[] values = adapter.getItemNames();
		builder.setItems(values, new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
				OnContextMenuClick clk = adapter.getClickAdapter(which);
				if (clk != null) {
					clk.onContextMenuClick(adapter.getItemId(which), which, false, dialog);
				}
			}

		});
		builder.show();
	}

	
	private void basicFileOperation(final LocalIndexInfo info, ContextMenuAdapter adapter) {
		OnContextMenuClick listener = new OnContextMenuClick() {
			@Override
			public void onContextMenuClick(int resId, int pos, boolean isChecked, DialogInterface dialog) {
				if (resId == R.string.local_index_mi_rename) {
					renameFile(info);
				} else if (resId == R.string.local_index_mi_restore) {
					new LocalIndexOperationTask(RESTORE_OPERATION).execute(info);
				} else if (resId == R.string.local_index_mi_delete) {
					Builder confirm = new AlertDialog.Builder(getActivity());
					confirm.setPositiveButton(R.string.default_buttons_yes, new DialogInterface.OnClickListener() {
						@Override
						public void onClick(DialogInterface dialog, int which) {
							new LocalIndexOperationTask(DELETE_OPERATION).execute(info);	
						}
					});
					confirm.setNegativeButton(R.string.default_buttons_no, null);
					confirm.setMessage(getString(R.string.delete_confirmation_msg, info.getFileName()));
					confirm.show();
				} else if (resId == R.string.local_index_mi_backup) {
					new LocalIndexOperationTask(BACKUP_OPERATION).execute(info);
				}
			}
		};
		if(info.getType() == LocalIndexType.MAP_DATA || info.getType() == LocalIndexType.SRTM_DATA){
			if(!info.isBackupedData()){
				adapter.item(R.string.local_index_mi_backup).listen(listener).position( 1).reg();
			}
		}
		if(info.isBackupedData()){
			adapter.item(R.string.local_index_mi_restore).listen(listener).position(2).reg();
		}
		adapter.item(R.string.local_index_mi_rename).listen(listener).position(3).reg();
		adapter.item(R.string.local_index_mi_delete).listen(listener).position(4).reg();
	}
	
	private void renameFile(LocalIndexInfo info) {
		final File f = new File(info.getPathToData());
		Builder b = new AlertDialog.Builder(getActivity());
		if(f.exists()){
			final EditText editText = new EditText(getActivity());
			editText.setText(f.getName());
			b.setView(editText);
			b.setPositiveButton(R.string.default_buttons_save, new DialogInterface.OnClickListener() {
				
				@Override
				public void onClick(DialogInterface dialog, int which) {
					String newName = editText.getText().toString();
					File dest = new File(f.getParentFile(), newName);
					if (dest.exists()) {
						AccessibleToast.makeText(getDownloadActivity(), R.string.file_with_name_already_exists, Toast.LENGTH_LONG).show();
					} else {
						if(!f.getParentFile().exists()) {
							f.getParentFile().mkdirs();
						}
						if(f.renameTo(dest)){
							reloadIndexes();
						} else {
							AccessibleToast.makeText(getDownloadActivity(), R.string.file_can_not_be_renamed, Toast.LENGTH_LONG).show();
						}
					}
					
				}
			});
			b.setNegativeButton(R.string.default_buttons_cancel, null);
			b.show();
		}
	}

	public class LoadLocalIndexTask extends AsyncTask<Activity, LocalIndexInfo, List<LocalIndexInfo>> {

		private List<LocalIndexInfo> result;

		@Override
		protected List<LocalIndexInfo> doInBackground(Activity... params) {
			LocalIndexHelper helper = new LocalIndexHelper(getMyApplication());
			return helper.getLocalIndexData(this);
		}

		public void loadFile(LocalIndexInfo... loaded) {
			publishProgress(loaded);
		}

		@Override
		protected void onPreExecute() {
			getDownloadActivity().setSupportProgressBarIndeterminateVisibility(true);
		}

		@Override
		protected void onProgressUpdate(LocalIndexInfo... values) {
			for (LocalIndexInfo v : values) {
				listAdapter.addLocalIndexInfo(v);
			}
			listAdapter.notifyDataSetChanged();
		}

		public void setResult(List<LocalIndexInfo> result) {
			this.result = result;
			if(result == null){
				listAdapter.clear();
			} else {
				for (LocalIndexInfo v : result) {
					listAdapter.addLocalIndexInfo(v);
				}
				listAdapter.notifyDataSetChanged();
				onPostExecute(result);
			}
		}

		@Override
		protected void onPostExecute(List<LocalIndexInfo> result) {
			this.result = result;
			getDownloadActivity().setSupportProgressBarIndeterminateVisibility(false);
		}

		public List<LocalIndexInfo> getResult() {
			return result;
		}

	}

	private File getFileToRestore(LocalIndexInfo i){
		if(i.isBackupedData()){
			File parent = new File(i.getPathToData()).getParentFile();
			if(i.getType() == LocalIndexType.SRTM_DATA){
				parent = getMyApplication().getAppPath(IndexConstants.SRTM_INDEX_DIR);
			} else if(i.getType() == LocalIndexType.MAP_DATA){
				parent = getMyApplication().getAppPath(IndexConstants.MAPS_PATH);
			} else if(i.getType() == LocalIndexType.TILES_DATA){
				parent = getMyApplication().getAppPath(IndexConstants.TILES_INDEX_DIR);
			} else if(i.getType() == LocalIndexType.VOICE_DATA){
				parent = getMyApplication().getAppPath(IndexConstants.VOICE_INDEX_DIR);
			} else if(i.getType() == LocalIndexType.TTS_VOICE_DATA){
				parent = getMyApplication().getAppPath(IndexConstants.VOICE_INDEX_DIR);
			}
			return new File(parent, i.getFileName());
		}
		return new File(i.getPathToData());
	}
	
	private File getFileToBackup(LocalIndexInfo i) {
		if(!i.isBackupedData()){
			return new File(getMyApplication().getAppPath(IndexConstants.BACKUP_INDEX_DIR), i.getFileName());
		}
		return new File(i.getPathToData());
	}
	
	private boolean move(File from, File to){
		if(!to.getParentFile().exists()){
			to.getParentFile().mkdirs();
		}
		return from.renameTo(to);
	}
	
	public class LocalIndexOperationTask extends AsyncTask<LocalIndexInfo, LocalIndexInfo, String> {
		
		private final int operation;

		public LocalIndexOperationTask(int operation){
			this.operation = operation;
		}
		
		
		@Override
		protected String doInBackground(LocalIndexInfo... params) {
			int count = 0;
			int total = 0;
			for(LocalIndexInfo info : params) {
				if(!isCancelled()){
					boolean successfull = false;
					if(operation == DELETE_OPERATION){
						File f = new File(info.getPathToData());
						successfull = Algorithms.removeAllFiles(f);
					} else if(operation == RESTORE_OPERATION){
						successfull = move(new File(info.getPathToData()), getFileToRestore(info));
						if(successfull){
							info.setBackupedData(false);
						}
					} else if(operation == BACKUP_OPERATION){
						successfull = move(new File(info.getPathToData()), getFileToBackup(info));
						if(successfull){
							info.setBackupedData(true);
						}
					}
					total ++;
					if(successfull){
						count++;
						publishProgress(info);
					}
				}
			}
			if(operation == DELETE_OPERATION){
				return getString(R.string.local_index_items_deleted, count, total);
			} else if(operation == BACKUP_OPERATION){
				return getString(R.string.local_index_items_backuped, count, total);
			} else if(operation == RESTORE_OPERATION){
				return getString(R.string.local_index_items_restored, count, total);
			}  
			
			return "";
		}


		@Override
		protected void onProgressUpdate(LocalIndexInfo... values) {
			if(operation == DELETE_OPERATION){
				listAdapter.delete(values);
			} else if(operation == BACKUP_OPERATION){
				listAdapter.move(values, false);
			} else if(operation == RESTORE_OPERATION){
				listAdapter.move(values, true);
			}
			
		}
		
		@Override
		protected void onPreExecute() {
		 getDownloadActivity().setProgressBarIndeterminateVisibility(true);
		}

		@Override
		protected void onPostExecute(String result) {
			getDownloadActivity().setProgressBarIndeterminateVisibility(false);
			AccessibleToast.makeText(getDownloadActivity(), result, Toast.LENGTH_LONG).show();
			listAdapter.clear();
			reloadIndexes();
			
		}
	}
	
	

	public class LoadLocalIndexDescriptionTask extends AsyncTask<LocalIndexInfo, LocalIndexInfo, LocalIndexInfo[]> {

		@Override
		protected LocalIndexInfo[] doInBackground(LocalIndexInfo... params) {
			LocalIndexHelper helper = new LocalIndexHelper(getMyApplication());
			for (LocalIndexInfo i : params) {
				helper.updateDescription(i);
			}
			return params;
		}

		@Override
		protected void onPreExecute() {
		}

		@Override
		protected void onProgressUpdate(LocalIndexInfo... values) {
			listAdapter.notifyDataSetChanged();
		}

		@Override
		protected void onPostExecute(LocalIndexInfo[] result) {
			listAdapter.notifyDataSetChanged();
		}

	}

	@Override
	public boolean onChildClick(ExpandableListView parent, View v, int groupPosition, int childPosition, long id) {
		LocalIndexInfo item = listAdapter.getChild(groupPosition, childPosition);
		item.setExpanded(!item.isExpanded());
		if (item.isExpanded()) {
			descriptionLoader = new LoadLocalIndexDescriptionTask();
			descriptionLoader.execute(item);
		}
		if(selectionMode){
			selectedItems.add(item);
		}
		listAdapter.notifyDataSetInvalidated();
		return true;
	}
	
	public Set<LocalIndexInfo> getSelectedItems() {
		return selectedItems;
	}
	
	


	@Override
	public void onPause() {
		super.onPause();
		if(operationTask != null){
			operationTask.cancel(true);
		}
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		asyncLoader.cancel(true);
		descriptionLoader.cancel(true);
	}
	
//	@Override
//	public Object onRetainNonConfigurationInstance() {
//		if(asyncLoader != null){
//			return asyncLoader.getResult();
//		}
//		return super.onRetainNonConfigurationInstance();
//	}

	@Override
	public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
		//fixes issue when local files not shown after switching tabs
		reloadIndexes();
		ActionBar actionBar = getDownloadActivity().getSupportActionBar();
		//hide action bar from downloadindexfragment
		actionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_STANDARD);
		optionsMenuAdapter = new ContextMenuAdapter(getDownloadActivity());
		OnContextMenuClick listener = new OnContextMenuClick() {
			@Override
			public void onContextMenuClick(int itemId, int pos, boolean isChecked, DialogInterface dialog) {
				localOptionsMenu(itemId);
			}
		};
		optionsMenuAdapter.item(R.string.local_index_mi_reload)
						.icons(R.drawable.ic_action_refresh_dark, R.drawable.ic_action_refresh_light)
						.listen(listener).position(1).reg();
		optionsMenuAdapter.item(R.string.local_index_mi_backup)
						.icons(R.drawable.ic_action_undo_dark, R.drawable.ic_action_undo_light)
						.listen(listener).position(2).reg();
		optionsMenuAdapter.item(R.string.local_index_mi_restore)
				.icons(R.drawable.ic_action_redo_dark, R.drawable.ic_action_redo_dark)
						.listen(listener).position(3).reg();
		optionsMenuAdapter.item(R.string.local_index_mi_delete)
						.icons(R.drawable.ic_action_delete_dark, R.drawable.ic_action_delete_light)
						.listen(listener).position(4).reg();
		OsmandPlugin.onOptionsMenuActivity(getDownloadActivity(), null, optionsMenuAdapter);
		// doesn't work correctly
		int max =  getResources().getInteger(R.integer.abs__max_action_buttons);
		SubMenu split = null;
		for (int j = 0; j < optionsMenuAdapter.length(); j++) {
			MenuItem item;
			if (j + 1 >= max && optionsMenuAdapter.length() > max) {
				if (split == null) {
					split = menu.addSubMenu(0, 1, 0, R.string.default_buttons_other_actions);
					split.setIcon(isLightActionBar() ? R.drawable.abs__ic_menu_moreoverflow_holo_light
							: R.drawable.abs__ic_menu_moreoverflow_holo_dark);
					split.getItem().setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
				}
				item = split.add(0, optionsMenuAdapter.getItemId(j), j + 1, optionsMenuAdapter.getItemName(j));
				item.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM );
			} else {
				item = menu.add(0, optionsMenuAdapter.getItemId(j), j + 1, optionsMenuAdapter.getItemName(j));
				item.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM  
						);
			}
			if (optionsMenuAdapter.getImageId(j, isLightActionBar()) != 0) {
				item.setIcon(optionsMenuAdapter.getImageId(j, isLightActionBar()));
			}
			
		}

		if(operationTask == null || operationTask.getStatus() == AsyncTask.Status.FINISHED){
			menu.setGroupVisible(0, true);
		} else {
			menu.setGroupVisible(0, false);
		}
	}
	
	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		int itemId = item.getItemId();
		for (int i = 0; i < optionsMenuAdapter.length(); i++) {
			if (itemId == optionsMenuAdapter.getItemId(i)) {
				optionsMenuAdapter.getClickAdapter(i).onContextMenuClick(itemId, i, false, null);
				return true;
			}
		}
		return super.onOptionsItemSelected(item);
	}
	
	public void doAction(int actionResId){
		if(actionResId == R.string.local_index_mi_backup){
			operationTask = new LocalIndexOperationTask(BACKUP_OPERATION);
		} else if(actionResId == R.string.local_index_mi_delete){
			operationTask = new LocalIndexOperationTask(DELETE_OPERATION);
		} else if(actionResId == R.string.local_index_mi_restore){
			operationTask = new LocalIndexOperationTask(RESTORE_OPERATION);
		} else {
			operationTask = null;
		}
		if(operationTask != null){
			operationTask.execute(selectedItems.toArray(new LocalIndexInfo[selectedItems.size()]));
		}
		if(actionMode != null) {
			actionMode.finish();
		}
	}
	
	
	private void collapseAllGroups() {
		for (int i = 0; i < listAdapter.getGroupCount(); i++) {
			getExpandableListView().collapseGroup(i);
		}

	}
	
	private void openSelectionMode(final int actionResId, final int actionIconId, 
			final DialogInterface.OnClickListener listener){
		String value = getString(actionResId);
		if (value.endsWith("...")) {
			value = value.substring(0, value.length() - 3);
		}
		final String actionButton = value;
		if(listAdapter.getGroupCount() == 0){
			listAdapter.cancelFilter();
			AccessibleToast.makeText(getDownloadActivity(), getString(R.string.local_index_no_items_to_do, actionButton.toLowerCase()), Toast.LENGTH_SHORT).show();
			return;
		}
		collapseAllGroups();
		
		selectionMode = true;
		selectedItems.clear();
		actionMode = getDownloadActivity().startActionMode(new Callback() {

			@Override
			public boolean onCreateActionMode(ActionMode mode, Menu menu) {
				selectionMode = true;
				MenuItem it = menu.add(actionResId);
				if (actionIconId != 0) {
					it.setIcon(actionIconId);
				}
				it.setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_IF_ROOM |
						MenuItem.SHOW_AS_ACTION_WITH_TEXT);
				return true;
			}

			@Override
			public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
				return false;
			}

			@Override
			public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
				if (selectedItems.isEmpty()) {
					AccessibleToast.makeText(getDownloadActivity(),
							getString(R.string.local_index_no_items_to_do, actionButton.toLowerCase()), Toast.LENGTH_SHORT).show();
					return true;
				}

				Builder builder = new AlertDialog.Builder(getDownloadActivity());
				builder.setMessage(getString(R.string.local_index_action_do, actionButton.toLowerCase(), selectedItems.size()));
				builder.setPositiveButton(actionButton, listener);
				builder.setNegativeButton(R.string.default_buttons_cancel, null);
				builder.show();
				return true;
			}

			@Override
			public void onDestroyActionMode(ActionMode mode) {
				selectionMode = false;
				descriptionText.setVisibility(View.VISIBLE);
				updateDescriptionTextWithSize();
				listAdapter.cancelFilter();
				collapseAllGroups();
				listAdapter.notifyDataSetChanged();
			}

		});
		//findViewById(R.id.DescriptionText).setVisibility(View.GONE);
		listAdapter.notifyDataSetChanged();
	}
	
	private void updateDescriptionTextWithSize(){
		File dir = getMyApplication().getAppPath("").getParentFile();
		String size = formatGb.format(new Object[]{0});
		if(dir.canRead()){
			StatFs fs = new StatFs(dir.getAbsolutePath());
			size = formatGb.format(new Object[]{(float) (fs.getAvailableBlocks()) * fs.getBlockSize() / (1 << 30) }); 
		}

		String text = getString(R.string.local_index_description, size);
		int l = text.indexOf('.');
		if(l == -1) {
			l = text.length();
		}
		descriptionText.setText(text);
		descriptionText.setMovementMethod(LinkMovementMethod.getInstance());
	}

	public void localOptionsMenu(final int itemId) {
		if (itemId == R.string.local_index_mi_reload) {
			reloadIndexes();
		} else if (itemId == R.string.local_index_mi_delete) {
			openSelectionMode(itemId, R.drawable.ic_action_delete_dark, R.drawable.ic_action_delete_light,
					new DialogInterface.OnClickListener() {

						@Override
						public void onClick(DialogInterface dialog, int which) {
							doAction(itemId);
						}
					}, null, null);
		} else if (itemId == R.string.local_index_mi_backup) {
			openSelectionMode(itemId, R.drawable.ic_action_undo_dark, R.drawable.ic_action_undo_light,
					new DialogInterface.OnClickListener() {

						@Override
						public void onClick(DialogInterface dialog, int which) {
							doAction(itemId);
						}
					}, Boolean.FALSE, LocalIndexType.MAP_DATA);
		} else if (itemId == R.string.local_index_mi_restore) {
			openSelectionMode(itemId, R.drawable.ic_action_redo_dark, R.drawable.ic_action_redo_light,
					new DialogInterface.OnClickListener() {

						@Override
						public void onClick(DialogInterface dialog, int which) {
							doAction(itemId);
						}
					}, Boolean.TRUE, LocalIndexType.MAP_DATA);
			listAdapter.filterCategories(true);
		}
	}
	
	public void openSelectionMode(int stringRes, int darkIcon, int lightIcon, DialogInterface.OnClickListener listener, Boolean backup,
			LocalIndexType filter) {
		if (backup != null) {
			listAdapter.filterCategories(backup);
		}
		if (filter != null) {
			listAdapter.filterCategories(filter);
		}
		openSelectionMode(stringRes, !isLightActionBar() ? darkIcon : lightIcon, listener);
	}
	

	public void reloadIndexes() {
		listAdapter.clear();
		asyncLoader = new LoadLocalIndexTask();
		AsyncTask<Void, String, List<String>> task = new AsyncTask<Void, String, List<String>>(){

			@Override
			protected void onPostExecute(List<String> warnings) {
				getDownloadActivity().setProgressBarIndeterminateVisibility(false);
				if (!warnings.isEmpty()) {
					final StringBuilder b = new StringBuilder();
					boolean f = true;
					for (String w : warnings) {
						if (f) {
							f = false;
						} else {
							b.append('\n');
						}
						b.append(w);
					}
					AccessibleToast.makeText(getDownloadActivity(), b.toString(), Toast.LENGTH_LONG).show();
				}
				if(asyncLoader.getStatus() == Status.PENDING) {
					asyncLoader.execute(getDownloadActivity());
				}
			}
			
			@Override
			protected void onPreExecute() {
				super.onPreExecute();
				getDownloadActivity().setProgressBarIndeterminateVisibility(true);
			}
			@Override
			protected List<String> doInBackground(Void... params) {
				return getMyApplication().getResourceManager().reloadIndexes(IProgress.EMPTY_PROGRESS);
			}
			
		};
		task.execute();
		
	}

	

	protected class LocalIndexesAdapter extends OsmandBaseExpandableListAdapter {
		
		Map<LocalIndexInfo, List<LocalIndexInfo>> data = new LinkedHashMap<LocalIndexInfo, List<LocalIndexInfo>>();
		List<LocalIndexInfo> category = new ArrayList<LocalIndexInfo>();
		List<LocalIndexInfo> filterCategory = null;
		int warningColor;
		int okColor;
		int defaultColor;
		int corruptedColor;
		Context ctx;

		public LocalIndexesAdapter(Context ctx) {
			this.ctx = ctx;
			warningColor = ctx.getResources().getColor(R.color.color_warning);
			okColor = ctx.getResources().getColor(R.color.color_ok);
			TypedArray ta = ctx.getTheme().obtainStyledAttributes(new int[]{android.R.attr.textColorPrimary});
			defaultColor = ta.getColor(0, ctx.getResources().getColor(R.color.color_unknown));
			ta.recycle();
			corruptedColor = ctx.getResources().getColor(R.color.color_invalid);
		}
		
		public void clear() {
			data.clear();
			category.clear();
			filterCategory = null;
			notifyDataSetChanged();
		}

		public LocalIndexInfo findCategory(LocalIndexInfo val, boolean backuped){
			for(LocalIndexInfo i : category){
				if(i.isBackupedData() == backuped && val.getType() == i.getType() && 
						Algorithms.objectEquals(i.getSubfolder(), val.getSubfolder())){
					return i;
				}
			}
			LocalIndexInfo newCat = new LocalIndexInfo(val.getType(), backuped, val.getSubfolder());
			category.add(newCat);
			data.put(newCat, new ArrayList<LocalIndexInfo>());
			return newCat;
		}
		
		public void delete(LocalIndexInfo[] values) {
			for(LocalIndexInfo i : values){
				LocalIndexInfo c = findCategory(i, i.isBackupedData());
				if(c != null){
					data.get(c).remove(i);
				}
			}
			listAdapter.notifyDataSetChanged();
		}
		
		public void move(LocalIndexInfo[] values, boolean oldBackupState) {
			for(LocalIndexInfo i : values){
				LocalIndexInfo c = findCategory(i, oldBackupState);
				if(c != null){
					data.get(c).remove(i);
				}
				c = findCategory(i, !oldBackupState);
				if(c != null){
					data.get(c).add(i);
				}
			}
			listAdapter.notifyDataSetChanged();
		}

		public void cancelFilter(){
			filterCategory = null;
			notifyDataSetChanged();
		}
		
		public void filterCategories(LocalIndexType... types) {
			List<LocalIndexInfo> filter = new ArrayList<LocalIndexInfo>();
			List<LocalIndexInfo> source = filterCategory == null ? category : filterCategory;
			for (LocalIndexInfo info : source) {
				for (LocalIndexType ts : types) {
					if (info.getType() == ts) {
						filter.add(info);
					}
				}
			}
			filterCategory = filter;
			notifyDataSetChanged();
		}
		
		public void filterCategories(boolean backup) {
			List<LocalIndexInfo> filter = new ArrayList<LocalIndexInfo>();
			List<LocalIndexInfo> source = filterCategory == null ? category : filterCategory;
			for (LocalIndexInfo info : source) {
				if (info.isBackupedData() == backup) {
					filter.add(info);
				}
			}
			filterCategory = filter;
			notifyDataSetChanged();
		}

		public void addLocalIndexInfo(LocalIndexInfo info) {
			int found = -1;
			// search from end
			for (int i = category.size() - 1; i >= 0; i--) {
				LocalIndexInfo cat = category.get(i);
				if (cat.getType() == info.getType() && info.isBackupedData() == cat.isBackupedData() &&
						Algorithms.objectEquals(info.getSubfolder(), cat.getSubfolder())) {
					found = i;
					break;
				}
			}
			if (found == -1) {
				found = category.size();
				category.add(new LocalIndexInfo(info.getType(), info.isBackupedData(), info.getSubfolder()));
			}
			if (!data.containsKey(category.get(found))) {
				data.put(category.get(found), new ArrayList<LocalIndexInfo>());
			}
			data.get(category.get(found)).add(info);
		}

		@Override
		public LocalIndexInfo getChild(int groupPosition, int childPosition) {
			LocalIndexInfo cat = filterCategory != null ? filterCategory.get(groupPosition) : category.get(groupPosition);
			return data.get(cat).get(childPosition);
		}

		@Override
		public long getChildId(int groupPosition, int childPosition) {
			// it would be unusable to have 10000 local indexes
			return groupPosition * 10000 + childPosition;
		}

		@Override
		public View getChildView(final int groupPosition, final int childPosition, boolean isLastChild, View convertView, ViewGroup parent) {
			View v = convertView;
			final LocalIndexInfo child = (LocalIndexInfo) getChild(groupPosition, childPosition);
			if (v == null ) {
				LayoutInflater inflater = (LayoutInflater) getDownloadActivity().getSystemService(Context.LAYOUT_INFLATER_SERVICE);
				v = inflater.inflate(net.osmand.plus.R.layout.local_index_list_item, parent, false);
			}
			TextView viewName = ((TextView) v.findViewById(R.id.local_index_name));
			String mapDescr = getMapDescription(child.getFileName());
			String mapName = FileNameTranslationHelper.getFileName(ctx, ((OsmandApplication) getDownloadActivity().getApplication()).getResourceManager().getOsmandRegions(), child.getFileName());

			if (mapDescr.length() > 0){
				viewName.setText(mapDescr + " - " + mapName);
			} else {
				viewName.setText(mapName);
			}
			if (child.isNotSupported()) {
				viewName.setTextColor(warningColor);
				viewName.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
			} else if (child.isCorrupted()) {
				viewName.setTextColor(corruptedColor);
				viewName.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
			} else if (child.isLoaded()) {
				// users confused okColor here with "uptodate", so let's leave white (black in dark app theme) as "isLoaded"
				//viewName.setTextColor(okColor);
				viewName.setTextColor(defaultColor);
				viewName.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
			} else {
				viewName.setTextColor(defaultColor);
				viewName.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
				if (child.isBackupedData()) {
					viewName.setTypeface(Typeface.DEFAULT, Typeface.ITALIC);
				}
			}
			if (child.getSize() >= 0) {
				String size;
				if (child.getSize() > 100) {
					size = formatMb.format(new Object[] { (float) child.getSize() / (1 << 10) });
				} else {
					size = child.getSize() + " kB";
				}
				((TextView) v.findViewById(R.id.local_index_size)).setText(size);
			} else {
				((TextView) v.findViewById(R.id.local_index_size)).setText("");
			}
			TextView descr = ((TextView) v.findViewById(R.id.local_index_descr));
			if (child.isExpanded()) {
				descr.setVisibility(View.VISIBLE);
				descr.setText(child.getDescription());
			} else {
				descr.setVisibility(View.GONE);
			}
			final CheckBox checkbox = (CheckBox) v.findViewById(R.id.check_local_index);
			checkbox.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
			if (selectionMode) {
				checkbox.setChecked(selectedItems.contains(child));
				checkbox.setOnClickListener(new View.OnClickListener() {
					
					@Override
					public void onClick(View v) {
						if(checkbox.isChecked()){
							selectedItems.add(child);
						} else {
							selectedItems.remove(child);
						}
					}
				});
			}
			

			return v;
		}

		@Override
		public View getGroupView(int groupPosition, boolean isExpanded, View convertView, ViewGroup parent) {
			View v = convertView;
			LocalIndexInfo group = getGroup(groupPosition);
			if (v == null) {
				LayoutInflater inflater = (LayoutInflater) getDownloadActivity().getSystemService(Context.LAYOUT_INFLATER_SERVICE);
				v = inflater.inflate(net.osmand.plus.R.layout.expandable_list_item_category, parent, false);
			}
			StringBuilder t = new StringBuilder(group.getType().getHumanString(getDownloadActivity()));
			if(group.getSubfolder() != null) {
				t.append(" ").append(group.getSubfolder());
			}
			if (group.isBackupedData()) {
				t.append(" - ").append(getString(R.string.local_indexes_cat_backup));
			}
			adjustIndicator(groupPosition, isExpanded, v);
			TextView nameView = ((TextView) v.findViewById(R.id.category_name));
			List<LocalIndexInfo> list = data.get(group);
			int size = 0;
			for(int i=0; i<list.size(); i++){
				int sz = list.get(i).getSize();
				if(sz < 0){
					size = 0;
					break;
				} else {
					size += sz;
				}
			}
			size = size / (1 << 10);
			if(size > 0){
				t.append(" [").append(size).append(" MB]");
			}
			nameView.setText(t.toString());
			if (!group.isBackupedData()) {
				nameView.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
			} else {
				nameView.setTypeface(Typeface.DEFAULT, Typeface.ITALIC);
			}

			return v;
		}

		@Override
		public int getChildrenCount(int groupPosition) {
			LocalIndexInfo cat = filterCategory != null ? filterCategory.get(groupPosition) : category.get(groupPosition);
			return data.get(cat).size();
		}

		@Override
		public LocalIndexInfo getGroup(int groupPosition) {
			return filterCategory == null ?  category.get(groupPosition)  : filterCategory.get(groupPosition);
		}

		@Override
		public int getGroupCount() {
			return filterCategory == null ?  category.size() : filterCategory.size();
		}

		@Override
		public long getGroupId(int groupPosition) {
			return groupPosition;
		}

		@Override
		public boolean hasStableIds() {
			return false;
		}

		@Override
		public boolean isChildSelectable(int groupPosition, int childPosition) {
			return true;
		}



		private String getMapDescription(String fileName){
			int ls = fileName.lastIndexOf(".");
			String name = fileName;
			if (ls >= 0) {
				name = fileName.substring(0, ls);
			}

			if (name.endsWith("-roads")){
					return ctx.getString(R.string.download_roads_only_item);
			}

			return "";
		}
	}

	private DownloadActivity getDownloadActivity(){ return (DownloadActivity)getActivity();}
}
