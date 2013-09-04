package de.blau.android.easyedit;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.Toast;

import com.actionbarsherlock.view.ActionMode;
import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuItem;

import de.blau.android.DialogFactory;
import de.blau.android.Logic;
import de.blau.android.Main;
import de.blau.android.R;
import de.blau.android.osm.Node;
import de.blau.android.osm.OsmElement;
import de.blau.android.osm.Relation;
import de.blau.android.osm.RelationMember;
import de.blau.android.osm.Way;
import de.blau.android.prefs.Preferences;
import de.blau.android.util.GeoMath;

/**
 * This class handles most of the EasyEdit mode actions, to keep it separate from the main class.
 * @author Jan
 *
 */
public class EasyEditManager {

	private final Main main;
	private final Logic logic;
	/** the touch listener from Main */
	
	private ActionMode currentActionMode = null;
	private EasyEditActionModeCallback currentActionModeCallback = null;
	
	public EasyEditManager(Main main, Logic logic) {
		this.main = main;
		this.logic = logic;
	}
	
	public boolean isProcessingAction() {
		return (currentActionModeCallback != null);
	}
	
	/**
	 * Call to let the action mode (if any) have a first go at the click.
	 * @param x the x coordinate (screen coordinate?) of the click
	 * @param y the y coordinate (screen coordinate?) of the click
	 * @return true if the click was handled
	 */
	public boolean actionModeHandledClick(float x, float y) {
		return (currentActionModeCallback != null && currentActionModeCallback.handleClick(x, y));
	}
	
	/**
	 * Handle case where nothing is touched.
	 */
	public void nothingTouched() {
		// User clicked an empty area. If something is selected, deselect it.
		if (currentActionModeCallback instanceof ElementSelectionActionModeCallback) currentActionMode.finish();
		logic.setSelectedNode(null);
		logic.setSelectedWay(null);
		logic.setSelectedRelationWays(null);
		logic.setSelectedRelationNodes(null);
	}
	
	/**
	 * Set relation members to be highlighted
	 * @param r
	 */
	public void selectRelation(Relation r) {
		for (RelationMember rm : r.getMembers()) {
			OsmElement e = rm.getElement();
			if (e != null) {
				if (e.getName().equals("way")) {
					logic.addSelectedRelationWay((Way) e);
				} else if (e.getName().equals("node")) {
					logic.addSelectedRelationNode((Node) e);
				} 
			}
		}
	}
	
	/**
	 * Handle editing the given element.
	 * @param element The OSM element to edit.
	 */
	public void editElement(OsmElement element) {
		if (currentActionModeCallback == null || !currentActionModeCallback.handleElementClick(element)) {
			// No callback or didn't handle the click, perform default (select element)
			ActionMode.Callback cb = null;
			if (element instanceof Node) cb = new NodeSelectionActionModeCallback((Node)element);
			if (element instanceof Way ) cb = new  WaySelectionActionModeCallback((Way )element);
			if (element instanceof Relation ) cb = new RelationSelectionActionModeCallback((Relation )element);
			if (cb != null) {
				main.startActionMode(cb);
				Toast.makeText(main, element.getDescription(), Toast.LENGTH_SHORT).show();
			}
		}
	}
	
	/** This gets called when the map is long-pressed in easy-edit mode */
	public boolean handleLongClick(View v, float x, float y) {

		if (currentActionModeCallback instanceof PathCreationActionModeCallback) {
			// we don't do long clicks while creating paths
			return false;
		}
		v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
		// TODO: Need to patch ABS, see https://github.com/JakeWharton/ActionBarSherlock/issues/642
		if (main.startActionMode(new LongClickActionModeCallback(x, y)) == null) {
			main.startActionMode(new PathCreationActionModeCallback(x, y));
		}
		return true;
	}
	
	public void invalidate() {
		if (currentActionMode != null) {
			currentActionMode.invalidate();
		}
	}
	
	/**
	 * Takes a parameter for a node and one for a way.
	 * If the way is not null, opens a tag editor for the way.
	 * Otherwise, opens a tag editor for the node
	 * (unless the node is also null, then nothing happens).
	 * @param possibleNode a node that was edited, or null
	 * @param possibleWay a way that was edited, or null
	 */
	private void tagApplicable(Node possibleNode, Way possibleWay) {
		if (possibleWay == null) {
			// Single node was added
			if (possibleNode != null) { // null-check to be sure
				main.startActionMode(new NodeSelectionActionModeCallback(possibleNode));
				main.performTagEdit(possibleNode, null);
			}
		} else { // way was added
			main.startActionMode(new WaySelectionActionModeCallback(possibleWay));
			main.performTagEdit(possibleWay, null);		
		}
	}
	
	/**
	 * Finds which ways can be merged with a way.
	 * For this, the ways must not be equal, need to share at least one end node,
	 * and either at least one of them must not have tags, or the tags on both ways must be equal.  
	 * 
	 * @param way the way into which other ways may be merged
	 * @return a list of all ways which can be merged into the given way
	 */
	private Set<OsmElement> findMergeableWays(Way way) {
		Set<Way> candidates = new HashSet<Way>();
		Set<OsmElement> result = new HashSet<OsmElement>();
		candidates.addAll(logic.getWaysForNode(way.getFirstNode()));
		candidates.addAll(logic.getWaysForNode(way.getLastNode()));
		for (Way candidate : candidates) {
			if ((way != candidate)
				&& (candidate.isEndNode(way.getFirstNode()) || candidate.isEndNode(way.getLastNode()))
				&& (candidate.getTags().isEmpty() || way.getTags().isEmpty() || 
						way.getTags().entrySet().equals(candidate.getTags().entrySet()) )
						//TODO check for relations too
				) {
				result.add(candidate);
			}
		}
		return result;
	}
	
	/**
	 * Finds which nodes can be append targets.
	 * @param way The way that will be appended to.
	 * @return The set of nodes suitable for appending.
	 */
	private Set<OsmElement> findAppendableNodes(Way way) {
		Set<OsmElement> result = new HashSet<OsmElement>();
		for (Node node : way.getNodes()) {
			if (way.isEndNode(node)) result.add(node);
		}
		// don't allow appending to circular ways
		if (result.size() == 1) result.clear();
		return result;
	}
	
	/**
	 * Finds which ways or nodes can be used as a via element in a restriction relation
	 * 
	 * @param way the from way
	 * @return a list of all applicable objects
	 */
	private Set<OsmElement> findViaElements(Way way) {
		Set<Way> candidates = new HashSet<Way>();
		Set<OsmElement> result = new HashSet<OsmElement>();
		candidates.addAll(logic.getWaysForNode(way.getFirstNode()));
		candidates.addAll(logic.getWaysForNode(way.getLastNode()));
		boolean firstNodeAdded = false;
		boolean lastNodeAdded = false;
		for (Way candidate : candidates) {
			if ((way != candidate) && (way.getTagWithKey("highway") != null)) {
				if (candidate.isEndNode(way.getFirstNode())) {
					result.add(candidate);
					if (!firstNodeAdded) {
						firstNodeAdded = true;
						result.add(way.getFirstNode());
					}
				} else if (candidate.isEndNode(way.getLastNode())) {
					result.add(candidate);
					if (!lastNodeAdded) {
						lastNodeAdded = true;
						result.add(way.getLastNode());
					}
				}
				
			}
		}
		return result;
	}
	
	/**
	 * Find possible elements for the "to" role of a restriction relation
	 * @param way
	 * @param commonNode
	 * @return
	 */
	private Set<OsmElement> findToElements(Way way, Node commonNode) {
		Set<Way> candidates = new HashSet<Way>();
		Set<OsmElement> result = new HashSet<OsmElement>();
		candidates.addAll(logic.getWaysForNode(commonNode));
		for (Way candidate : candidates) {
			if ((way != candidate) && (way.getTagWithKey("highway") != null)) {
					result.add(candidate);
			}
		}
		return result;
	}
	
	/**
	 * Base class for ActionMode callbacks inside {@link EasyEditManager}.
	 * Derived classes should call {@link #onCreateActionMode(ActionMode, Menu)} and {@link #onDestroyActionMode(ActionMode)}.
	 * It will handle registering and de-registering the action mode callback with the {@link EasyEditManager}.
	 * When the {@link EasyEditManager} receives a click on a node or way, it may pass it to the current action mode callback.
	 * The callback can then swallow it by returning true or allow the default handling to happen by returning false
	 * in the {@link #handleNodeClick(Node)} or {@link #handleWayClick(Way)} methods.
	 * 
	 * @author Jan
	 *
	 */
	public abstract class EasyEditActionModeCallback implements ActionMode.Callback {
		
		@Override
		public boolean onCreateActionMode(ActionMode mode, Menu menu) {
			currentActionMode = mode;
			currentActionModeCallback = this;
			Log.d("EasyEditActionModeCallback", "onCreateActionMode");
			return false;
		}
		
		@Override
		public void onDestroyActionMode(ActionMode mode) {
			currentActionMode = null;
			currentActionModeCallback = null;
			logic.hideCrosshairs();
			main.invalidateMap();
			Log.d("EasyEditActionModeCallback", "onDestroyActionMode");
		}
		
		/**
		 * This method gets called when the map is clicked, before checking for clicked nodes/ways.
		 * The ActionModeCallback can then either return true to indicate that the click was handled (or should be ignored),
		 * or return false to indicate default handling should apply
		 * (which includes checking for node/way clicks and calling the corresponding methods).
		 * 
		 * @param x the x screen coordinate of the click
		 * @param y the y screen coordinate of the click
		 * @return true if the click has been handled, false if default handling should apply
		 */
		public boolean handleClick(float x, float y) {
			return false;
		}
		
		/**
		 * This method gets called when an OsmElement click has to be handled.
		 * The ActionModeCallback can then either return true to indicate that the click was handled (or should be ignored),
		 * or return false to indicate default handling should apply.
		 * @param element the OsmElement that was clicked
		 * @return true if the click has been handled, false if default handling should apply
		 */
		public boolean handleElementClick(OsmElement element) {
			return false;
		}
		
		/** {@inheritDoc} */ // placed here for convenience, allows to avoid unnecessary methods in subclasses
		@Override
		public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
			return false;
		}
		
		/** {@inheritDoc} */ // placed here for convenience, allows to avoid unnecessary methods in subclasses
		@Override
		public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
			Log.e("EasyEditActionModeCallback", "onActionItemClicked");
			return false;
		}
	}
	
	private class LongClickActionModeCallback extends EasyEditActionModeCallback {
		private static final int MENUITEM_OSB = 1;
		private static final int MENUITEM_NEWNODEWAY = 2;
		private static final int MENUITEM_PASTE = 3;
		private float startX;
		private float startY;
		private int startLon;
		private int startLat;
		private float x;
		private float y;
		
		private boolean isNeeded() {
			// Test if this action mode is actually necessary
			Preferences prefs = new Preferences(main.getApplicationContext());
			return prefs.isOpenStreetBugsEnabled();
		}
		
		public LongClickActionModeCallback(float x, float y) {
			super();
			this.x = x;
			this.y = y;
		}
		
		@Override
		public boolean onCreateActionMode(ActionMode mode, Menu menu) {
			super.onCreateActionMode(mode, menu);
			mode.setTitle(R.string.menu_add);
			// show crosshairs 
			logic.showCrosshairs(x, y);
			startX = x;
			startY = y;
			startLon = logic.xtoLonE7(x);
			startLat = logic.ytoLonE7(y);
			// return isNeeded();
			// always required for paste
			return true;
		}
		
		@Override
		public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
			super.onPrepareActionMode(mode, menu);
			menu.clear();
			menu.add(Menu.NONE, MENUITEM_OSB, Menu.NONE, R.string.openstreetbug_new_bug).setIcon(R.drawable.tag_menu_bug);
			menu.add(Menu.NONE, MENUITEM_NEWNODEWAY, Menu.NONE, R.string.openstreetbug_new_nodeway).setIcon(R.drawable.tag_menu_append);
			if (!logic.clipboardIsEmpty()) {
				menu.add(Menu.NONE, MENUITEM_PASTE, Menu.NONE, R.string.menu_paste);
			}
			return true;
		}
		
		/**
		 * if we get a short click go to path creation mode
		 */
		@Override
		public boolean handleClick(float x, float y) {
			PathCreationActionModeCallback pcamc = new PathCreationActionModeCallback(logic.lonE7toX(startLon), logic.latE7toY(startLat));
			main.startActionMode(pcamc);
			pcamc.handleClick(x, y);
			logic.hideCrosshairs();
			return true;
		}
		
		@Override
		public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
			super.onActionItemClicked(mode, item);
			switch (item.getItemId()) {
			case MENUITEM_OSB:
				// todo check if authenticated
				mode.finish();
				logic.setSelectedBug(logic.makeNewBug(x, y));
				main.showDialog(DialogFactory.OPENSTREETBUG_EDIT);
				logic.hideCrosshairs();
				return true;
			case MENUITEM_NEWNODEWAY:
				main.startActionMode(new PathCreationActionModeCallback(x, y));
				logic.hideCrosshairs();
				return true;
			case MENUITEM_PASTE:
				logic.pasteFromClipboard(startX, startY);
				logic.hideCrosshairs();
				mode.finish();
				return true;
			default:
				Log.e("LongClickActionModeCallback", "Unknown menu item");
				break;
			}
			return false;
		}
	}
	
	/**
	 * This callback handles path creation. It is started after a long-press.
	 * During this action mode, clicks are handled by custom code.
	 * The node and way click handlers are thus never called.
	 */
	private class PathCreationActionModeCallback extends EasyEditActionModeCallback {
		private static final int MENUITEM_UNDO = 1;
		
		/** x coordinate of first node */
		private float x;
		/** y coordinate of first node */
		private float y;
		/** Node to append to */
		private Node appendTargetNode;
		/** Way to append to */
		private Way appendTargetWay;
		
		/** contains a pointer to the created way if one was created. used to fix selection after undo. */
		private Way createdWay = null;
		/** contains a list of created nodes. used to fix selection after undo. */
		private ArrayList<Node> createdNodes = new ArrayList<Node>();
		
		public PathCreationActionModeCallback(float x, float y) {
			super();
			this.x = x;
			this.y = y;
			appendTargetNode = null;
			appendTargetWay = null;
		}
		
		public PathCreationActionModeCallback(Node node) {
			super();
			appendTargetNode = node;
			appendTargetWay = null;
		}
		
		public PathCreationActionModeCallback(Way way, Node node) {
			super();
			appendTargetNode = node;
			appendTargetWay = way;
		}
		
		@Override
		public boolean onCreateActionMode(ActionMode mode, Menu menu) {
			super.onCreateActionMode(mode, menu);
			mode.setTitle(R.string.actionmode_createpath);
			logic.setSelectedWay(null);
			logic.setSelectedNode(appendTargetNode);
			if (appendTargetNode != null) {
				if (appendTargetWay != null) {
					logic.performAppendStart(appendTargetWay, appendTargetNode);
				} else {
					logic.performAppendStart(appendTargetNode);
				}
			} else {
				pathCreateNode(x, y);
			}
			logic.hideCrosshairs();
			return true;
		}
		
		@Override
		public boolean handleClick(float x, float y) {
			super.handleClick(x, y);
			pathCreateNode(x, y);
			return true;
		}
		
		/**
		 * Creates/adds a node into a path during path creation
		 * @param x x screen coordinate
		 * @param y y screen coordinate
		 */
		private void pathCreateNode(float x, float y) {
			Node lastSelectedNode = logic.getSelectedNode();
			Way lastSelectedWay = logic.getSelectedWay();
			if (appendTargetNode != null) {
				logic.performAppendAppend(x, y);
			} else {
				logic.performAdd(x, y);
			}
			if (logic.getSelectedNode() == null) {
				// user clicked last node again -> finish adding
				currentActionMode.finish();
				tagApplicable(lastSelectedNode, lastSelectedWay); //TODO doesn't deselect way after tag edit
			} else { // update cache for undo
				createdWay = logic.getSelectedWay();
				if (createdWay != null) {
					createdNodes = new ArrayList<Node>(createdWay.getNodes());
				} else {
					createdNodes = new ArrayList<Node>();
					createdNodes.add(logic.getSelectedNode());
				}
			}
			main.invalidateMap();
		}
		
		@Override
		public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
			super.onPrepareActionMode(mode, menu);
			menu.clear();
			menu.add(Menu.NONE, MENUITEM_UNDO, Menu.NONE, R.string.undo).setIcon(R.drawable.undo);
			return true;
		}
		
		@Override
		public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
			super.onActionItemClicked(mode, item);
			switch (item.getItemId()) {
			case MENUITEM_UNDO:
				handleUndo();
				break;
			default:
				Log.e("PathCreationActionModeCallback", "Unknown menu item");
				break;
			}
			return false;
		}
		
		private void handleUndo() {
			logic.getUndo().undo();
			if (logic.getSelectedNode() == null) { // should always happen, node removed
				 Iterator<Node> nodeIterator = createdNodes.iterator();
				 while (nodeIterator.hasNext()) { // remove nodes that do not exist anymore
					 if (!logic.exists(nodeIterator.next())) nodeIterator.remove();
				 }
				 if (createdNodes.isEmpty()) {
					 // all nodes have been deleted, cancel action mode
					 currentActionMode.finish();
				 } else {
					 // select last node
					 logic.setSelectedNode(createdNodes.get(createdNodes.size()-1));
				 }
			}
			createdWay = logic.getSelectedWay(); // will be null if way was deleted by undo
			main.invalidateMap();
		}
		
		/**
		 * Path creation action mode is ending
		 */
		@Override
		public void onDestroyActionMode(ActionMode mode) {
			Node lastSelectedNode = logic.getSelectedNode();
			Way lastSelectedWay = logic.getSelectedWay();
			logic.setSelectedWay(null);
			logic.setSelectedNode(null);
			if (appendTargetNode == null) tagApplicable(lastSelectedNode, lastSelectedWay);
			super.onDestroyActionMode(mode);
		}
	}
	
	/**
	 * This action mode handles element selection. When a node or way should be selected, just start this mode.
	 * The element will be automatically selected, and a second click on the same element will open the tag editor.
	 * @author Jan
	 *
	 */
	private abstract class ElementSelectionActionModeCallback extends EasyEditActionModeCallback {
		private static final int MENUITEM_TAG = 1;
		private static final int MENUITEM_DELETE = 2;
		private static final int MENUITEM_HISTORY = 3; 
		private static final int MENUITEM_COPY = 4;
		private static final int MENUITEM_CUT = 5;
		private static final int MENUITEM_RELATION = 6;
		
		
		protected OsmElement element = null;
		
		public ElementSelectionActionModeCallback(OsmElement element) {
			super();
			this.element = element;
		}
		
		/**
		 * Internal helper to avoid duplicate code in {@link #handleNodeClick(Node)} and {@link #handleWayClick(Way)}.
		 * @param element clicked element
		 * @return true if handled, false if default handling should apply
		 */
		@Override
		public boolean handleElementClick(OsmElement element) {
			super.handleElementClick(element);
			if (element == this.element) {
				main.performTagEdit(element, null);
				return true;
			}
			return false;
		}
		
		@Override
		public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
			super.onPrepareActionMode(mode, menu);
			menu.clear();
			menu.add(Menu.NONE, MENUITEM_TAG, Menu.NONE, R.string.menu_tags).setIcon(R.drawable.tag_menu_tags);
			menu.add(Menu.NONE, MENUITEM_DELETE, Menu.NONE, R.string.delete).setIcon(R.drawable.tag_menu_delete);

			if (!(element instanceof Relation)) {
				menu.add(Menu.NONE, MENUITEM_COPY, Menu.NONE, R.string.menu_copy);
				menu.add(Menu.NONE, MENUITEM_CUT, Menu.NONE, R.string.menu_cut);
			}
			menu.add(Menu.NONE, MENUITEM_RELATION, Menu.CATEGORY_SECONDARY, R.string.menu_relation);
			if (element.getOsmId() > 0){
				menu.add(Menu.NONE, MENUITEM_HISTORY, Menu.CATEGORY_SECONDARY, R.string.menu_history).setIcon(R.drawable.tag_menu_history);
			}
			return true;
		}
		
		@Override
		public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
			super.onActionItemClicked(mode, item);
			switch (item.getItemId()) {
			case MENUITEM_TAG: main.performTagEdit(element, null); break;
			case MENUITEM_DELETE: menuDelete(mode); break;
			case MENUITEM_HISTORY: showHistory(); break;
			case MENUITEM_COPY: logic.copyToClipboard(element); break;
			case MENUITEM_CUT: logic.cutToClipboard(element); break;
			case MENUITEM_RELATION: main.startActionMode(new  AddRelationMemberActionModeCallback((Way)element)); break;
			default: return false;
			}
			return true;
		}
		
		protected abstract void menuDelete(ActionMode mode);
		
		/**
		 * Opens the history page of the selected element in a browser
		 */
		private void showHistory() {
			Intent intent = new Intent(Intent.ACTION_VIEW);
			intent.setData(Uri.parse(main.getBaseURL()+"browse/"+element.getName()+"/"+element.getOsmId()+"/history"));
			intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			main.startActivity(intent);
		}
		
		/**
		 * Element selection action mode is ending
		 */
		@Override
		public void onDestroyActionMode(ActionMode mode) {
			logic.setSelectedNode(null);
			logic.setSelectedWay(null);
			super.onDestroyActionMode(mode);
		}
	}
	
	private class NodeSelectionActionModeCallback extends ElementSelectionActionModeCallback {
		private static final int MENUITEM_APPEND = 7;
		private static final int MENUITEM_JOIN = 8;
		private static final int MENUITEM_UNJOIN = 9;
		
		private OsmElement joinableElement = null;
		
		private NodeSelectionActionModeCallback(Node node) {
			super(node);
		}
		
		@Override
		public boolean onCreateActionMode(ActionMode mode, Menu menu) {
			super.onCreateActionMode(mode, menu);
			logic.setSelectedNode((Node)element);
			logic.setSelectedWay(null);
			main.invalidateMap();
			mode.setTitle(R.string.actionmode_nodeselect);
			return true;
		}
		
		@Override
		public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
			super.onPrepareActionMode(mode, menu);
			if (logic.isEndNode((Node)element)) {
				menu.add(Menu.NONE, MENUITEM_APPEND, Menu.NONE, R.string.menu_append).setIcon(R.drawable.tag_menu_append);
			}
			joinableElement = logic.findJoinableElement((Node)element);
			if (joinableElement != null) {
				menu.add(Menu.NONE, MENUITEM_JOIN, Menu.NONE, R.string.menu_join).setIcon(R.drawable.tag_menu_merge);
			}
			if (logic.getWaysForNode((Node)element).size() > 1) {
				menu.add(Menu.NONE, MENUITEM_UNJOIN, Menu.NONE, R.string.menu_unjoin).setIcon(R.drawable.tag_menu_split);
			}
			return true;
		}
		
		@Override
		public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
			if (!super.onActionItemClicked(mode, item)) {
				switch (item.getItemId()) {
				case MENUITEM_APPEND:
					main.startActionMode(new PathCreationActionModeCallback((Node)element));
					break;
				case MENUITEM_JOIN:
					if (!logic.performJoin(joinableElement, (Node)element)) {
						Toast.makeText(main, R.string.toast_merge_tag_conflict, Toast.LENGTH_LONG).show();
						main.performTagEdit((Node)element, null);
					} else {
						mode.finish();
					}
					break;
				case MENUITEM_UNJOIN:
					logic.performUnjoin((Node)element);
					mode.finish();
					break;
				default: return false;
				}
			}
			return true;
		}
		
		protected void menuDelete(ActionMode mode) {
			if (element.hasParentRelations()) {
				new AlertDialog.Builder(main)
					.setTitle(R.string.delete)
					.setMessage(R.string.deletenode_relation_description)
					.setPositiveButton(R.string.deletenode,
						new DialogInterface.OnClickListener() {	
							@Override
							public void onClick(DialogInterface dialog, int which) {
								logic.performEraseNode((Node)element);
								currentActionMode.finish();
							}
						})
					.show();
			} else {
				logic.performEraseNode((Node)element);
				mode.finish();
			}
		}
		
	}
	
	private class WaySelectionActionModeCallback extends ElementSelectionActionModeCallback {
		private static final int MENUITEM_SPLIT = 7;
		private static final int MENUITEM_MERGE = 8;
		private static final int MENUITEM_REVERSE = 9;
		private static final int MENUITEM_APPEND = 10;
		private static final int MENUITEM_RESTRICTION = 11;
		private static final int MENUITEM_ROTATE = 12;
		
		private Set<OsmElement> cachedMergeableWays;
		private Set<OsmElement> cachedAppendableNodes;
		private Set<OsmElement> cachedViaElements;
		
		private WaySelectionActionModeCallback(Way way) {
			super(way);
			Log.d("WaySelectionActionCallback", "constructor");
			cachedMergeableWays = findMergeableWays(way);
			cachedAppendableNodes = findAppendableNodes(way);
			cachedViaElements = findViaElements(way);
		}
		
		@Override
		public boolean onCreateActionMode(ActionMode mode, Menu menu) {
			super.onCreateActionMode(mode, menu);
			Log.d("WaySelectionActionCallback", "onCreateActionMode");
			logic.setSelectedNode(null);
			logic.setSelectedWay((Way)element);
			main.invalidateMap();
			mode.setTitle(R.string.actionmode_wayselect);
			return true;
		}
		
		@Override
		public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
			super.onPrepareActionMode(mode, menu);
			Log.d("WaySelectionActionCallback", "onPrepareActionMode");
			menu.add(Menu.NONE, MENUITEM_REVERSE, Menu.NONE, R.string.menu_reverse).setIcon(R.drawable.tag_menu_reverse);
			if (((Way)element).getNodes().size() > 2) {
				menu.add(Menu.NONE, MENUITEM_SPLIT, Menu.NONE, R.string.menu_split).setIcon(R.drawable.tag_menu_split);
			}
			if (cachedMergeableWays.size() > 0) {
				menu.add(Menu.NONE, MENUITEM_MERGE, Menu.NONE, R.string.menu_merge).setIcon(R.drawable.tag_menu_merge);
			}
			if (cachedAppendableNodes.size() > 0) {
				menu.add(Menu.NONE, MENUITEM_APPEND, Menu.NONE, R.string.menu_append).setIcon(R.drawable.tag_menu_append);
			}
			if ((((Way)element).getTagWithKey("highway") != null) && (cachedViaElements.size() > 0)) {
				menu.add(Menu.NONE, MENUITEM_RESTRICTION, Menu.NONE, R.string.menu_restriction).setIcon(R.drawable.tag_menu_restriction);
			}	
			menu.add(Menu.NONE, MENUITEM_ROTATE, Menu.NONE, R.string.menu_rotate);
			return true;
		}
		
		private void reverseWay() {
			final Way way = (Way) element;
			if (way.notReversable()) {
				new AlertDialog.Builder(main)
				.setTitle(R.string.menu_reverse)
				.setMessage(R.string.notreversable_description)
				.setPositiveButton(R.string.reverse_anyway,
					new DialogInterface.OnClickListener() {	
						@Override
						public void onClick(DialogInterface dialog, int which) {
							if (logic.performReverse(way)) { // true if it had oneway tag
								Toast.makeText(main, R.string.toast_oneway_reversed, Toast.LENGTH_LONG).show();
								main.performTagEdit(way, null);
							}
						}
					})
				.show();		
			} else if (logic.performReverse(way)) { // true if it had oneway tag
				Toast.makeText(main, R.string.toast_oneway_reversed, Toast.LENGTH_LONG).show();
				main.performTagEdit(way, null);
			}
		}
		
		@Override
		public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
			if (!super.onActionItemClicked(mode, item)) {
				switch (item.getItemId()) {
				case MENUITEM_SPLIT: main.startActionMode(new WaySplittingActionModeCallback((Way)element)); break;
				case MENUITEM_MERGE: main.startActionMode(new WayMergingActionModeCallback((Way)element, cachedMergeableWays)); break;
				case MENUITEM_REVERSE: reverseWay(); break;
				case MENUITEM_APPEND: main.startActionMode(new WayAppendingActionModeCallback((Way)element, cachedAppendableNodes)); break;
				case MENUITEM_RESTRICTION: main.startActionMode(new  RestrictionFromElementActionModeCallback((Way)element, cachedViaElements)); break;
				case MENUITEM_ROTATE: logic.setRoationMode(); logic.showCrosshairsForCentroid(); break;
				default: return false;
				}
			}
			return true;
		}
		
		protected void menuDelete(ActionMode mode) {
			boolean isRelationMember = element.hasParentRelations();
			new AlertDialog.Builder(main)
				.setTitle(R.string.delete)
				.setMessage(isRelationMember ? R.string.deleteway_relation_description : R.string.deleteway_description)
				.setPositiveButton(R.string.deleteway_wayonly,
					new DialogInterface.OnClickListener() {	
						@Override
						public void onClick(DialogInterface dialog, int which) {
							logic.performEraseWay((Way)element, false);
							currentActionMode.finish();
						}
					})
				.setNeutralButton(R.string.deleteway_wayandnodes,
					new DialogInterface.OnClickListener() {	
						@Override
						public void onClick(DialogInterface dialog, int which) {
							logic.performEraseWay((Way)element, true);
							currentActionMode.finish();
						}
					})
				.show();
		}	
	}
	
	private class WaySplittingActionModeCallback extends EasyEditActionModeCallback {
		private Way way;
		private Set<OsmElement> nodes = new HashSet<OsmElement>();
		
		public WaySplittingActionModeCallback(Way way) {
			super();
			this.way = way;
			nodes.addAll(way.getNodes());
			if (!way.isClosed()) { 
				nodes.remove(way.getFirstNode());
				nodes.remove(way.getLastNode());
			} 
		}
		
		@Override
		public boolean onCreateActionMode(ActionMode mode, Menu menu) {
			super.onCreateActionMode(mode, menu);
			if (way.isClosed())
				mode.setTitle(R.string.menu_closed_way_split_1);
			else
				mode.setTitle(R.string.menu_split);
			logic.setClickableElements(nodes);
			logic.setReturnRelations(false);
			return true;
		}
		
		@Override
		public boolean handleElementClick(OsmElement element) { // due to clickableElements, only valid nodes can be clicked
			super.handleElementClick(element);
			if (way.isClosed())
				main.startActionMode(new ClosedWaySplittingActionModeCallback(way, (Node) element));
			else {
				logic.performSplit(way, (Node)element);
				currentActionMode.finish();
			}
			return true;
		}
		
		@Override
		public void onDestroyActionMode(ActionMode mode) {
			logic.setClickableElements(null);
			logic.setReturnRelations(true);
			super.onDestroyActionMode(mode);
		}	
	}
	
	private class ClosedWaySplittingActionModeCallback extends EasyEditActionModeCallback {
		private Way way;
		private Node node;
		private Set<OsmElement> nodes = new HashSet<OsmElement>();
		
		public ClosedWaySplittingActionModeCallback(Way way, Node node) {
			super();
			this.way = way;
			this.node = node;
			nodes.addAll(way.getNodes());;
			nodes.remove(node);
		}
		
		@Override
		public boolean onCreateActionMode(ActionMode mode, Menu menu) {
			super.onCreateActionMode(mode, menu);
			mode.setTitle(R.string.menu_closed_way_split_2);
			logic.setClickableElements(nodes);
			logic.setReturnRelations(false);
			return true;
		}
		
		@Override
		public boolean handleElementClick(OsmElement element) { // due to clickableElements, only valid nodes can be clicked
			super.handleElementClick(element);
			logic.performClosedWaySplit(way, node, (Node)element);
			currentActionMode.finish();
			return true;
		}
		
		@Override
		public void onDestroyActionMode(ActionMode mode) {
			logic.setClickableElements(null);
			logic.setReturnRelations(true);
			super.onDestroyActionMode(mode);
		}	
	}
	
	private class WayMergingActionModeCallback extends EasyEditActionModeCallback {
		private Way way;
		private Set<OsmElement> ways;
		
		public WayMergingActionModeCallback(Way way, Set<OsmElement> mergeableWays) {
			super();
			this.way = way;
			ways = mergeableWays;
		}
		
		@Override
		public boolean onCreateActionMode(ActionMode mode, Menu menu) {
			mode.setTitle(R.string.menu_merge);
			logic.setClickableElements(ways);
			logic.setReturnRelations(false);
			super.onCreateActionMode(mode, menu);
			return true;
		}
		
		@Override
		public boolean handleElementClick(OsmElement element) { // due to clickableElements, only valid ways can be clicked
			super.handleElementClick(element);
			if (!logic.performMerge(way, (Way)element)) {
				Toast.makeText(main, R.string.toast_merge_tag_conflict, Toast.LENGTH_LONG).show();
				if (way.getState() != OsmElement.STATE_DELETED)
					main.performTagEdit(way, null);
				else
					main.performTagEdit(element, null);
			} else {
				if (way.getState() != OsmElement.STATE_DELETED)
					main.startActionMode(new WaySelectionActionModeCallback(way));
				else
					main.startActionMode(new WaySelectionActionModeCallback((Way)element));
			}
			return true;
		}
		
		@Override
		public void onDestroyActionMode(ActionMode mode) {
			logic.setClickableElements(null);
			logic.setReturnRelations(true);
			super.onDestroyActionMode(mode);
		}
	}
	
	private class WayAppendingActionModeCallback extends EasyEditActionModeCallback {
		private Way way;
		private Set<OsmElement> nodes;
		public WayAppendingActionModeCallback(Way way, Set<OsmElement> appendNodes) {
			super();
			this.way = way;
			nodes = appendNodes;
		}
		
		@Override
		public boolean onCreateActionMode(ActionMode mode, Menu menu) {
			mode.setTitle(R.string.menu_append);
			logic.setClickableElements(nodes);
			logic.setReturnRelations(false);
			super.onCreateActionMode(mode, menu);
			return true;
		}
		
		@Override
		public boolean handleElementClick(OsmElement element) { // due to clickableElements, only valid nodes can be clicked
			super.handleElementClick(element);
			main.startActionMode(new PathCreationActionModeCallback(way, (Node)element));
			return true;
		}
		
		@Override
		public void onDestroyActionMode(ActionMode mode) {
			logic.setClickableElements(null);
			logic.setReturnRelations(true);
			super.onDestroyActionMode(mode);
		}
	}
	
	private class RelationSelectionActionModeCallback extends ElementSelectionActionModeCallback {
	
		private RelationSelectionActionModeCallback(Relation relation) {
			super(relation);
		}
		
		@Override
		public boolean onCreateActionMode(ActionMode mode, Menu menu) {
			super.onCreateActionMode(mode, menu);
			logic.setSelectedNode(null);
			logic.setSelectedWay(null);
			selectRelation((Relation) element);
			main.invalidateMap();
			mode.setTitle(R.string.actionmode_relationselect);
			return true;
		}
		
		@Override
		public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
			super.onPrepareActionMode(mode, menu);

			return true;
		}
		
		@Override
		public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
			super.onActionItemClicked(mode, item);
			return true;
		}
		
		protected void menuDelete(ActionMode mode) {
			if (element.hasParentRelations()) {
				new AlertDialog.Builder(main)
					.setTitle(R.string.delete)
					.setMessage(R.string.deleterelation_relation_description)
					.setPositiveButton(R.string.deleterelation,
						new DialogInterface.OnClickListener() {	
							@Override
							public void onClick(DialogInterface dialog, int which) {
								logic.performEraseRelation((Relation)element);
								currentActionMode.finish();
							}
						})
					.show();
			} else {
				logic.performEraseRelation((Relation)element);
				mode.finish();
			}
		}
		
		@Override
		public void onDestroyActionMode(ActionMode mode) {
			logic.setClickableElements(null);
			logic.setReturnRelations(true);
			logic.setSelectedRelationWays(null);
			logic.setSelectedRelationNodes(null);
			logic.setSelectedNode(null);
			logic.setSelectedWay(null);
			super.onDestroyActionMode(mode);
		}
	}
	
	private class RestrictionFromElementActionModeCallback extends EasyEditActionModeCallback {
		private Way way;
		private Set<OsmElement> viaElements;
		public RestrictionFromElementActionModeCallback(Way way, Set<OsmElement> vias) {
			super();
			this.way = way;
			viaElements = vias;
		}
		
		@Override
		public boolean onCreateActionMode(ActionMode mode, Menu menu) {
			mode.setTitle(R.string.menu_restriction_via);
			logic.setClickableElements(viaElements);
			logic.setReturnRelations(false);
			logic.setSelectedRelationWays(null); // just to be safe
			logic.addSelectedRelationWay(way);
			logic.setSelectedNode(null);
			logic.setSelectedWay(null);
			super.onCreateActionMode(mode, menu);
			return true;
		}
		
		@Override
		public boolean handleElementClick(OsmElement element) { // due to clickableElements, only valid nodes can be clicked
			super.handleElementClick(element);
			main.startActionMode(new RestrictionViaElementActionModeCallback(way, element));
			return true;
		}
		
		@Override
		public void onDestroyActionMode(ActionMode mode) {
			logic.setClickableElements(null);
			logic.setReturnRelations(true);
			logic.setSelectedNode(null);
			logic.setSelectedWay(null);
			super.onDestroyActionMode(mode);
		}
	}
	
	private class RestrictionViaElementActionModeCallback extends EasyEditActionModeCallback {
		private Way fromWay;
		private OsmElement viaElement;
		private Set<OsmElement> cachedToElements;

		public RestrictionViaElementActionModeCallback(Way from, OsmElement via) {
			super();
			fromWay = from;
			viaElement = via;
			if (viaElement.getName().equals("node")) {
				cachedToElements = findToElements(fromWay, (Node) viaElement);
			} else {
				// need to find the right end of the way
				if (fromWay.hasNode(((Way) viaElement).getFirstNode())) {
					cachedToElements = findToElements((Way) viaElement, ((Way)viaElement).getLastNode());
				} else {
					cachedToElements = findToElements((Way) viaElement, ((Way)viaElement).getFirstNode());
				}
			}
		}
		
		@Override
		public boolean onCreateActionMode(ActionMode mode, Menu menu) {
			mode.setTitle(R.string.menu_restriction_to);
			logic.setClickableElements(cachedToElements);
			logic.setReturnRelations(false);
			if (viaElement.getName().equals("node")) {
				logic.addSelectedRelationNode((Node) viaElement);
			} else {
				logic.addSelectedRelationWay((Way) viaElement);
			}
			super.onCreateActionMode(mode, menu);
			return true;
		}
		
		@Override
		public boolean handleElementClick(OsmElement element) { // due to clickableElements, only valid elements can be clicked
			super.handleElementClick(element);
			main.startActionMode(new RestrictionToElementActionModeCallback(fromWay, viaElement, (Way) element));
			return true;
		}
		
		@Override
		public void onDestroyActionMode(ActionMode mode) {
			logic.setClickableElements(null);
			logic.setReturnRelations(true);
			logic.setSelectedNode(null);
			logic.setSelectedWay(null);
			super.onDestroyActionMode(mode);
		}
	}
	
	private class RestrictionToElementActionModeCallback extends EasyEditActionModeCallback {
		private Way fromWay;
		private OsmElement viaElement;
		private Way toWay;
		
		public RestrictionToElementActionModeCallback(Way from, OsmElement via, Way to) {
			super();
			fromWay = from;
			viaElement = via;
			toWay = to;
		}
		
		@Override
		public boolean onCreateActionMode(ActionMode mode, Menu menu) {
			mode.setTitle(R.string.menu_restriction);
			super.onCreateActionMode(mode, menu);
			logic.addSelectedRelationWay(toWay);
			Relation restriction = logic.createRestriction(fromWay, viaElement, toWay);
			Log.i("EasyEdit", "Created restriction");
			main.performTagEdit(restriction, "restriction"); //TODO mv restriction to constant
			return true;
		}
		
		@Override
		public void onDestroyActionMode(ActionMode mode) {
			logic.setClickableElements(null);
			logic.setReturnRelations(true);
			logic.setSelectedRelationWays(null);
			logic.setSelectedRelationNodes(null);
			logic.setSelectedNode(null);
			logic.setSelectedWay(null);
			super.onDestroyActionMode(mode);
		}
	}
	
	private class AddRelationMemberActionModeCallback extends EasyEditActionModeCallback {
		private static final int MENUITEM_REVERT = 1;

		private ArrayList<OsmElement> members;
		
		public AddRelationMemberActionModeCallback(OsmElement element) {
			super();
			members = new ArrayList<OsmElement>();
			addElement(element);
		}
		
		private void addElement(OsmElement element) {
			members.add(element);
			if (element.getName().equals("way"))
				logic.addSelectedRelationWay((Way)element);
			else if (element.getName().equals("node"))
				logic.addSelectedRelationNode((Node)element);
		}
		
		@Override
		public boolean onCreateActionMode(ActionMode mode, Menu menu) {
			mode.setTitle(R.string.menu_add_relation_member);
			super.onCreateActionMode(mode, menu);
			logic.setReturnRelations(false);
			logic.setClickableElements(logic.findClickableElements(members));
			menu.add(Menu.NONE, MENUITEM_REVERT, Menu.NONE, R.string.tag_menu_revert).setIcon(R.drawable.tag_menu_revert);
			menu.findItem(MENUITEM_REVERT).setVisible(false);
			return true;
		}
		
		@Override
		public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
			if (members.size() > 0)
				menu.findItem(MENUITEM_REVERT).setVisible(true);
			return true;
		}
		
		@Override
		public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
			if (!super.onActionItemClicked(mode, item)) {
				switch (item.getItemId()) {
				case MENUITEM_REVERT: // remove last item in list
					if(members.size() > 0) {
						OsmElement element = members.get(members.size()-1);
						if (element.getName().equals("way"))
							logic.removeSelectedRelationWay((Way)element);
						else if (element.getName().equals("node"))
							logic.removeSelectedRelationNode((Node)element);
						members.remove(element);
						logic.setClickableElements(logic.findClickableElements(members));
						main.invalidateMap();
						if (members.size() == 0)
							item.setVisible(false);
					}
					break;
				default: return false;
				}
			}
			return true;
		}
		
		@Override
		public boolean handleElementClick(OsmElement element) { // due to clickableElements, only valid elements can be clicked
			super.handleElementClick(element);
			addElement(element);
			logic.setClickableElements(logic.findClickableElements(members));
			return true;
		}
		
		@Override
		public void onDestroyActionMode(ActionMode mode) {
			super.onDestroyActionMode(mode);
			logic.setClickableElements(null);
			logic.setReturnRelations(true);
//			logic.setSelectedRelationWays(null);
//			logic.setSelectedRelationNodes(null);
			logic.setSelectedNode(null);
			logic.setSelectedWay(null);
			if (members.size() > 0)
				main.performTagEdit(logic.createRelation(null, members),"type");
		}
	}	
}
