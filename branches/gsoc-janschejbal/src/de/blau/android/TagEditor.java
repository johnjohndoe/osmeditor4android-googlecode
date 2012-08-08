package de.blau.android;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnDismissListener;
import android.content.Intent;
import android.content.SharedPreferences.Editor;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnKeyListener;
import android.view.ViewGroup.LayoutParams;
import android.view.ViewGroup.MarginLayoutParams;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import de.blau.android.osm.OsmElement;
import de.blau.android.osm.OsmElement.ElementType;
import de.blau.android.presets.Preset;
import de.blau.android.presets.Preset.PresetClickHandler;
import de.blau.android.presets.Preset.PresetGroup;
import de.blau.android.presets.Preset.PresetItem;
import de.blau.android.presets.PresetDialog;
import de.blau.android.presets.StreetTagValueAutocompletionAdapter;

/**
 * An Activity to edit OSM-Tags. Sends the edited Tags as Result to its caller-Activity (normally {@link Main}).
 * 
 * @author mb
 */
public class TagEditor extends Activity implements OnDismissListener {

	// TODO js autosuggest on focus, unless caused by automatic means
	
	public static final String TAGS = "tags";
	public static final String TAGS_ORIG = "tags_original";

	public static final String TYPE = "type";

	public static final String OSM_ID = "osm_id";

	/** The layout containing the entire editor */
	private LinearLayout verticalLayout = null;
	
	/** The layout containing the edit rows */
	private LinearLayout rowLayout = null;

	/**
	 * The tag we use for Android-logging.
	 */
//    @SuppressWarnings("unused")
	private static final String DEBUG_TAG = TagEditor.class.getName();

	private static final String PREF_LAST_TAG = "tagEditor.lastTagSet";

	private long osmId;

	private String type;

	/**
	 * The OSM element for reference.
	 * DO NOT ATTEMPT TO MODIFY IT.
	 */
	private OsmElement element;
	
	/**
	 * Handles "enter" key presses.
	 */
	private final OnKeyListener myKeyListener = new MyKeyListener();

	/** Set to true once values are loaded. used to suppress adding of empty rows while loading. */
	private boolean loaded;
	
	/** the Preset selection dialog used by this editor */
	private PresetDialog presetDialog;
	
	/**
	 * The tags present when this editor was created (for undoing changes)
	 */
	private ArrayList<String> originalTags;
	
	private PresetItem lastPresetItem = null;
	Preset preset = null;


	/**
	 * Interface for handling the key:value pairs in the TagEditor.
	 * @author Andrew Gregory
	 */
	private interface KeyValueHandler {
		abstract void handleKeyValue(final EditText keyEdit, final EditText valueEdit);
	}
	
	/**
	 * Perform some processing for each key:value pair in the TagEditor.
	 * @param handler The handler that will be called for each key:value pair.
	 */
	private void processKeyValues(final KeyValueHandler handler) {
		final int size = rowLayout.getChildCount();
		for (int i = 0; i < size; ++i) {
			View view = rowLayout.getChildAt(i);
			TagEditRow row = (TagEditRow)view;
			handler.handleKeyValue(row.keyEdit, row.valueEdit);
		}
	}
	
	
	/**
	 * Ensures that at least one empty row exists
	 */
	private void ensureEmptyRow() {
		if (!loaded) return;
		final int size = rowLayout.getChildCount();
		for (int i = 0; i < size; ++i) {
			View view = rowLayout.getChildAt(i);
			TagEditRow row = (TagEditRow)view;
			if (row.isEmpty()) return;
		}
		// no empty rows found, make one
		insertNewEdit("", "", -1);
	}
	
	@SuppressWarnings("unchecked")
	@Override
	protected void onCreate(final Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		//Not yet implemented by Google
		//getWindow().requestFeature(Window.FEATURE_CUSTOM_TITLE);
		//getWindow().setTitle(getString(R.string.tag_title) + " " + type + " " + osmId);

		// Disabled because it slows down the Motorola Milestone/Droid
		//getWindow().setFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND, WindowManager.LayoutParams.FLAG_BLUR_BEHIND);

		setContentView(R.layout.tag_view);

		verticalLayout = (LinearLayout) findViewById(R.id.vertical_layout);
		rowLayout = (LinearLayout) findViewById(R.id.edit_row_layout);
		loaded = false;
		if (savedInstanceState == null) {
			// No previous state to restore - get the state from the intent
			osmId = getIntent().getLongExtra(OSM_ID, 0);
			type = getIntent().getStringExtra(TYPE);
			originalTags = (ArrayList<String>)getIntent().getSerializableExtra(TAGS);
			loadEdits(originalTags);
		} else {
			// Restore activity from saved state
			osmId = savedInstanceState.getLong(OSM_ID, 0);
			type = savedInstanceState.getString(TYPE);
			loadEdits(savedInstanceState.getStringArrayList(TAGS));
			originalTags = savedInstanceState.getStringArrayList(TAGS_ORIG);
		}
		element = Main.logic.delegator.getOsmElement(type, osmId);
		preset = Main.getCurrentPreset();

		loaded = true;
		ensureEmptyRow();
		
		createSourceSurveyButton();
		createApplyPresetButton();
		createRepeatLastButton();
		createRevertButton();
		createOkButton();
		
		createRecentPresetView();
	}
	
	/**
	 * Given an edit field of an OSM key value, determine it's corresponding source key.
	 * For example, the source of "name" is "source:name". The source of "source" is
	 * "source". The source of "mf:name" is "mf.source:name".
	 * @param keyEdit The edit field of the key to be sourced.
	 * @return The source key for the given key.
	 */
	private static String sourceForKey(final String key) {
		String result = "source";
		if (key != null && !key.equals("") && !key.equals("source")) {
			// key is neither blank nor "source"
			// check if it's namespaced
			int i = key.indexOf(':');
			if (i == -1) {
				result = "source:" + key;
			} else {
				// handle already namespaced keys as per
				// http://wiki.openstreetmap.org/wiki/Key:source
				result = key.substring(0, i) + ".source" + key.substring(i);
			}
		}
		return result;
	}
	
	/**
	 * Create a source=survey button for tagging keys as "survey".
	 * Tapping the button will set (creating a new key/value if they don't exist)
	 * "source:key=survey", where key is the key of the currently focused key/value.
	 * If the key of the currently focused key/value is blank or "source", then the
	 * plain "source" key is used.
	 * For example, if you were editing the "name" key, then this would add
	 * "source:name=survey". On the other hand, if you had a blank key/value field
	 * focused, or were editing an existing "source" key/value, then "source=survey"
	 * would be set.
	 */
	private void createSourceSurveyButton() {
		Button sourcesurveyButton = (Button) findViewById(R.id.sourcesurveyButton);
		sourcesurveyButton.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(final View v) {
				// determine the key (if any) that has the current focus in the key or its value
				final String[] focusedKey = new String[]{null}; // array to work around unsettable final
				processKeyValues(new KeyValueHandler() {
					@Override
					public void handleKeyValue(final EditText keyEdit, final EditText valueEdit) {
						if (keyEdit.isFocused() || valueEdit.isFocused()) {
							focusedKey[0] = keyEdit.getText().toString().trim();
						}
					}
				});
				// ensure source(:key)=survey is tagged
				final String sourceKey = sourceForKey(focusedKey[0]);
				final boolean[] sourceSet = new boolean[]{false}; // array to work around unsettable final
				processKeyValues(new KeyValueHandler() {
					@Override
					public void handleKeyValue(final EditText keyEdit, final EditText valueEdit) {
						if (!sourceSet[0]) {
							String key = keyEdit.getText().toString().trim();
							String value = valueEdit.getText().toString().trim();
							// if there's a blank row - use them
							if (key.equals("") && value.equals("")) {
								key = sourceKey;
								keyEdit.setText(key);
							}
							if (key.equals(sourceKey)) {
								valueEdit.setText("survey");
								sourceSet[0] = true;
							}
						}
					}
				});
				if (!sourceSet[0]) {
					// source wasn't set above - add a new pair
					insertNewEdit(sourceKey, "survey", -1);
				}
			}
		});
	}
	
	private void createApplyPresetButton() {
		Button presetButton = (Button) findViewById(R.id.applyPresetButton);
		presetButton.setEnabled(Main.getCurrentPreset() != null);
		if (Main.getCurrentPreset() == null) return;
		
		presetButton.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(final View v) {
				showPresetDialog();
			}
		});
	}

	private void createRepeatLastButton() {
		Button button = (Button) findViewById(R.id.repeatLastButton);

		final String last = PreferenceManager.getDefaultSharedPreferences(this).getString(PREF_LAST_TAG, null);
		button.setEnabled(last != null);
		if (last == null) return;
		
		button.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(final View v) {
				rowLayout.removeAllViews();
				loadEdits(last);					
			}
		});

	}

	private void createRevertButton() {
		Button button = (Button) findViewById(R.id.revertButton);
		button.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(final View v) {
				rowLayout.removeAllViews();
				loadEdits(originalTags);
			}
		});
	}
	
	private void createOkButton() {
		Button okButton = (Button) findViewById(R.id.okButton);
		okButton.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(final View v) {
				sendResultAndFinish();
			}
		});
	}
	
	private void createRecentPresetView() {
		if (Main.getCurrentPreset() == null) return;
		
		ElementType filterType = element.getType();
		View v = Main.getCurrentPreset().getRecentPresetView(new PresetClickHandler() {
			
			@Override
			public void onItemClick(PresetItem item) {
				applyPreset(item);
			}
			
			@Override
			public void onGroupClick(PresetGroup group) {
				// should not have groups
			}
		},filterType);
		v.setBackgroundColor(0x80000000);
		v.setPadding(20, 20, 20, 20);
		v.setId(R.id.recentPresets);
		MarginLayoutParams p = new MarginLayoutParams(LayoutParams.FILL_PARENT, LayoutParams.WRAP_CONTENT);
		p.setMargins(10, 10, 10, 10);
		verticalLayout.addView(v);
	}

	
	/**
	 * Removes an old RecentPresetView and replaces it by a new one (to update it)
	 */
	private void recreateRecentPresetView() {
		View currentView = verticalLayout.findViewById(R.id.recentPresets);
		if (currentView != null) verticalLayout.removeView(currentView);
		createRecentPresetView();
	}
	
	@Override
	public boolean onCreateOptionsMenu(final Menu menu) {
		final MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.tag_menu, menu);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(final MenuItem item) {
		switch (item.getItemId()) {
		case R.id.tag_menu_mapfeatures:
			Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.link_mapfeatures)));
			startActivity(intent);
			return true;
		}

		return false;
	}

	@Override
	public boolean onKeyDown(final int keyCode, final KeyEvent event) {
		if (keyCode == KeyEvent.KEYCODE_BACK) {
			sendResultAndFinish();
			return true;
		}
		return super.onKeyDown(keyCode, event);
	}

	/**
	 * 
	 */
	protected void sendResultAndFinish() {
		// Save current tags for "repeat last" button
		Editor prefEdit = PreferenceManager.getDefaultSharedPreferences(this).edit();
		prefEdit.putString(PREF_LAST_TAG,getKeyValueString(false)).commit();
		
		Intent intent = new Intent();
		intent.putExtras(getKeyValueBundle(false)); // discards blank or partially blank pairs
		intent.putExtra(OSM_ID, osmId);
		intent.putExtra(TYPE, type);
		setResult(RESULT_OK, intent);
		finish();
	}

	/**
	 * Creates edits from an List containing tags (as sequential key-value pairs)
	 */
	protected void loadEdits(final List<String> tags) {
		loaded = false;
		for (int i = 0, size = tags.size(); i < size-1; i += 2) {
			insertNewEdit(tags.get(i), tags.get(i + 1), -1);
		}
		loaded = true;
		ensureEmptyRow();
	}
	
	/**
	 * Creates edits from a String containing newline-separated sequential key-value pairs
	 */
	protected void loadEdits(String tags) {
		if (tags.equals("")) {
			ensureEmptyRow();
			return;
		}
		String[] tagArray = tags.split("\n");
		loadEdits(Arrays.asList(tagArray));
	}

	/** Save the state of this activity instance for future restoration.
	 * @param outState The object to receive the saved state.
	 */
	@Override
	protected void onSaveInstanceState(final Bundle outState) {
		outState.putLong(OSM_ID, osmId);
		outState.putString(TYPE, type);
		outState.putStringArrayList(TAGS_ORIG, originalTags);
		outState.putAll(getKeyValueBundle(true)); // save partially blank pairs too
		super.onSaveInstanceState(outState);
	}
	
	/** When the Activity is interrupted, save MRUs*/
	@Override
	protected void onPause() {
		if (Main.getCurrentPreset() != null) Main.getCurrentPreset().saveMRU();
		super.onPause();
	}

	/**
	 * Insert a new row with one key and one value to edit.
	 * 
	 * @param aTagKey the key-value to start with
	 * @param aTagValue the value to start with.
	 * @param position the position where this should be inserted. set to -1 to insert at end, or 0 to insert at beginning.
	 */
	protected void insertNewEdit(final String aTagKey, final String aTagValue, final int position) {
		TagEditRow row = (TagEditRow)View.inflate(this, R.layout.tag_edit_row, null);
		row.setValues(aTagKey, aTagValue);
		rowLayout.addView(row, (position == -1) ? rowLayout.getChildCount() : position);
	}
	
	/**
	 * A row representing an editable tag, consisting of edits for key and value, labels and a delete button
	 * @author Jan
	 */
	public static class TagEditRow extends LinearLayout {

		private TagEditor owner;
		private AutoCompleteTextView keyEdit;
		private AutoCompleteTextView valueEdit;
		
		public TagEditRow(Context context) {
			super(context);
			owner = (TagEditor) (isInEditMode()?null:context); // Can only be instantiated inside TagEditor or in Eclipse
		}

		public TagEditRow(Context context, AttributeSet attrs) {
			super(context, attrs);
			owner = (TagEditor) (isInEditMode()?null:context); // Can only be instantiated inside TagEditor or in Eclipse
		}

		public TagEditRow(Context context, AttributeSet attrs, int defStyle) {
			super(context, attrs, defStyle);
			owner = (TagEditor) (isInEditMode()?null:context); // Can only be instantiated inside TagEditor or in Eclipse
		}
		
		@Override
		protected void onFinishInflate() {
			super.onFinishInflate();
			if (isInEditMode()) return; // allow visual editor to work
			
			keyEdit = (AutoCompleteTextView)findViewById(R.id.editKey);
			keyEdit.setOnKeyListener(owner.myKeyListener);
			//lastEditKey.setSingleLine(true);
			
			valueEdit = (AutoCompleteTextView)findViewById(R.id.editValue);
			valueEdit.setOnKeyListener(owner.myKeyListener);

			keyEdit.setOnFocusChangeListener(new OnFocusChangeListener() {
				@Override
				public void onFocusChange(View v, boolean hasFocus) {
					if (!hasFocus) return;
					keyEdit.setAdapter(getKeyAutocompleteAdapter());
				}
			});
			
			valueEdit.setOnFocusChangeListener(new OnFocusChangeListener() {
				@Override
				public void onFocusChange(View v, boolean hasFocus) {
					if (!hasFocus) return;
					valueEdit.setAdapter(getValueAutocompleteAdapter());
				}
			});
			
			View deleteIcon = findViewById(R.id.iconDelete);
			deleteIcon.setOnClickListener(new OnClickListener() {
				@Override
				public void onClick(View v) {
					deleteRow();
				}
			});
			
			
			OnClickListener autocompleteOnClick = new OnClickListener() {
				@Override
				public void onClick(View v) {
					if (v.hasFocus()) {
						((AutoCompleteTextView)v).showDropDown();
					}
				}
			};

			keyEdit.setOnClickListener(autocompleteOnClick);
			valueEdit.setOnClickListener(autocompleteOnClick);

			
			// This TextWatcher reacts to previously empty cells being filled to add additional rows where needed
			TextWatcher emptyWatcher = new TextWatcher() {
				private boolean wasEmpty;
				
				@Override
				public void onTextChanged(CharSequence s, int start, int before, int count) {
					// nop
				}
				
				@Override
				public void beforeTextChanged(CharSequence s, int start, int count, int after) {
					wasEmpty = TagEditRow.this.isEmpty();
				}
				
				@Override
				public void afterTextChanged(Editable s) {
					if (wasEmpty && s.length() > 0) {
						owner.ensureEmptyRow();
					}
				}
			};
			keyEdit.addTextChangedListener(emptyWatcher);
			valueEdit.addTextChangedListener(emptyWatcher);
		}
		

		protected ArrayAdapter<String> getKeyAutocompleteAdapter() {
			List<String> result = new ArrayList<String>();
			if (owner.lastPresetItem != null) {
				result.addAll(owner.lastPresetItem.getTags().keySet());
				result.addAll(owner.lastPresetItem.getRecommendedTags().keySet());
				result.addAll(owner.lastPresetItem.getOptionalTags().keySet());
			} else {
				// TODO js guess from other presets?
			}
			
			if (owner.preset != null) {
				result.addAll(owner.preset.getAutocompleteKeys(owner.element.getType()));
			}
			
			result.removeAll(owner.getUsedKeys(keyEdit));
			
			return new ArrayAdapter<String>(owner, R.layout.autocomplete_row, result);
		}

		protected ArrayAdapter<String> getValueAutocompleteAdapter() {
			String key = keyEdit.getText().toString();
			if (key == null || key.length() == 0) return null;
			
			boolean isStreetName = ( key.equalsIgnoreCase("addr:street") ||
					(key.equalsIgnoreCase("name") && owner.getUsedKeys(null).contains("highway")));
			if (isStreetName) {
				if (Main.logic == null || Main.logic.delegator == null) return null;
				ArrayAdapter<String> adapter = new StreetTagValueAutocompletionAdapter(owner,
						R.layout.autocomplete_row, Main.logic.delegator, owner.type, owner.osmId);
				// TODO js autocomplete the street name as soon as the addr:street is set (onTextChange?)
				if (valueEdit.getText().toString().length() == 0 && adapter.getCount() > 0) {
					valueEdit.setText(adapter.getItem(0));
				}			
			
				return null;
			} else {
				if (owner.preset == null) return null;
				Collection<String> values = owner.preset.getAutocompleteValues(owner.element.getType(), key);
				if (values == null || values.isEmpty()) return null;
				List<String> result = new ArrayList<String>(values);
				return new ArrayAdapter<String>(owner, R.layout.autocomplete_row, result);
			}
		}

		/**
		 * Sets key and value values
		 * @param aTagKey the key value to set
		 * @param aTagValue the value value to set
		 * @return the TagEditRow object for convenience
		 */
		public TagEditRow setValues(String aTagKey, String aTagValue) {
			keyEdit.setText(aTagKey);
			valueEdit.setText(aTagValue);
			return this;
		}
		
		/**
		 * Deletes this row
		 */
		public void deleteRow() {
			owner.rowLayout.removeView(this);
			if (isEmpty()) {
				owner.ensureEmptyRow();
			}
		}
		
		/**
		 * Checks if the fields in this row are empty
		 * @return true if both fields are empty, true if at least one is filled
		 */
		public boolean isEmpty() {
			return keyEdit.getText().toString().trim().equals("")
					&& valueEdit.getText().toString().trim().equals("");
		}

	}

	/**
	 * Collect all key-value pairs into a bundle to return them.
	 * 
	 * @param allowBlanks If true, includes key-value pairs where one or the other is blank.
	 * @return The bundle of key-value pairs.
	 */
	private Bundle getKeyValueBundle(final boolean allowBlanks) {
		final Bundle bundle = new Bundle(1);
		final ArrayList<String> tags = getKeyValueStringList(allowBlanks);
		bundle.putSerializable(TAGS, tags);
		return bundle;
	}
	
	/**
	 * Collect all key-value pairs into a single string
	 * 
	 * @param allowBlanks If true, includes key-value pairs where one or the other is blank.
	 * @return A string representing the key-value pairs
	 */
	private String getKeyValueString(final boolean allowBlanks) {
		final ArrayList<String> tags = getKeyValueStringList(allowBlanks);
		StringBuilder b = new StringBuilder();
		boolean empty = true;
		for (String entry : tags) {
			if (!empty) {
				b.append('\n');
			} else {
				empty = false;
			}
			b.append(entry);
		}
		return b.toString();
	}

	/**
	 * Collect all key-value pairs into an ArrayList<String>
	 * 
	 * @param allowBlanks If true, includes key-value pairs where one or the other is blank.
	 * @return The ArrayList<String> of key-value pairs.
	 */
	private ArrayList<String> getKeyValueStringList(final boolean allowBlanks) {
		final ArrayList<String> tags = new ArrayList<String>();
		processKeyValues(new KeyValueHandler() {
			@Override
			public void handleKeyValue(final EditText keyEdit, final EditText valueEdit) {
				String key = keyEdit.getText().toString().trim();
				String value = valueEdit.getText().toString().trim();
				boolean bothBlank = "".equals(key) && "".equals(value);
				boolean neitherBlank = !"".equals(key) && !"".equals(value);
				if (!bothBlank) {
					// both blank is never acceptable
					if (neitherBlank || allowBlanks) {
						tags.add(key);
						tags.add(value);
					}
				}
			}
		});
		return tags;
	}
	
	/**
	 * Get all key values currently in the editor, optionally skipping one field.
	 * @param ignoreEdit optional - if not null, this key field will be skipped,
	 *                              i.e. the key  in it will not be included in the output
	 * @return the list of all (or all but one) keys currently entered in the edit boxes
	 */
	private Set<String> getUsedKeys(final EditText ignoreEdit) {
		final HashSet<String> keys = new HashSet<String>();
		processKeyValues(new KeyValueHandler() {
			@Override
			public void handleKeyValue(final EditText keyEdit, final EditText valueEdit) {
				if (keyEdit.equals(ignoreEdit)) return;
				String key = keyEdit.getText().toString().trim();
				if (key.length() > 0) {
					keys.add(key);
				}
			}
		});
		return keys;
		
	}
	

	/**
	 * Insert a new row of key+value -edit-widgets if some text is entered into the current one.
	 * 
	 * @author <a href="mailto:Marcus@Wolschon.biz">Marcus Wolschon</a>
	 */
	private class MyKeyListener implements OnKeyListener {
		@Override
		public boolean onKey(final View view, final int keyCode, final KeyEvent keyEvent) {
			if (keyEvent.getAction() == KeyEvent.ACTION_UP || keyEvent.getAction() == KeyEvent.ACTION_MULTIPLE) {
				if (view instanceof EditText) {
					//on Enter -> goto next EditText
					if (keyCode == KeyEvent.KEYCODE_ENTER) {
						View nextView = view.focusSearch(View.FOCUS_RIGHT);
						if (!(nextView instanceof EditText)) {
							nextView = view.focusSearch(View.FOCUS_LEFT);
							if (nextView != null) {
								nextView = nextView.focusSearch(View.FOCUS_DOWN);
							}
						}

						if (nextView != null && nextView instanceof EditText) {
							nextView.requestFocus();
							return true;
						}
					}
				}
			}
			return false;
		}
	}

	/**
	 * @return the OSM ID of the element currently edited by the editor
	 */
	public long getOsmId() {
		return osmId;
	}

	/**
	 * Set the OSM ID currently edited by the editor
	 */
	public void setOsmId(final long osmId) {
		this.osmId = osmId;
	}

	public String getType() {
		return type;
	}

	public void setType(final String type) {
		this.type = type;
	}

	protected OnKeyListener getKeyListener() {
		return myKeyListener;
	}
	
	/**
	 * Shows the preset dialog for choosing which preset to apply
	 */
	private void showPresetDialog() {
		if (Main.getCurrentPreset() == null) return;
		presetDialog = new PresetDialog(this, Main.getCurrentPreset(), element);
		presetDialog.setOnDismissListener(this);
		presetDialog.show();
	}
	
	/**
	 * Handles the result from the preset dialog
	 * @param dialog
	 */
	@Override
	public void onDismiss(DialogInterface dialog) {
		PresetItem result = presetDialog.getDialogResult();
		if (result != null) {
			applyPreset(result);
		}
	}


	/**
	 * Applies a preset (e.g. selected from the dialog or MRU), i.e. adds the tags from the preset to the current tag set
	 * @param item the preset to apply
	 */
	private void applyPreset(PresetItem item) {
		lastPresetItem = item;
		int pos = 0;
		for (Entry<String, String> tag : item.getTags().entrySet()) {
			insertNewEdit(tag.getKey(), tag.getValue(), pos++);
		}
		for (Entry<String, String[]> tag : item.getRecommendedTags().entrySet()) {
			insertNewEdit(tag.getKey(), "", pos++);
		}
		if (Main.getCurrentPreset() != null) Main.getCurrentPreset().putRecentlyUsed(item);
		recreateRecentPresetView();
	}
}
