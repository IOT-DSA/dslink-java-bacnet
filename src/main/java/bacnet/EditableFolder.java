package bacnet;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.actions.Parameter;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.util.handler.Handler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.serotonin.bacnet4j.LocalDevice;
import com.serotonin.bacnet4j.type.enumerated.ObjectType;

public abstract class EditableFolder {

	private static final Logger LOGGER;

	static {
		LOGGER = LoggerFactory.getLogger(EditableFolder.class);
	}

	static final String ACTION_REMOVE = "remove";
	static final String ACTION_MAKE_COPY = "make copy";
	static final String ACTION_EDIT = "edit";
	static final String ACTION_ADD_OBJECT = "add object";
	static final String ACTION_ADD_FOLDER = "add folder";

	static final String ATTRIBUTE_NAME = "name";
	static final String ATTRIBUTE_OBJECT_TYPE = "object type";

	static final String ATTRIBUTE_RESTORE_TYPE = "restore type";
	static final String ATTRIBUTE_EDITABLE_FOLDER = "editable folder";
	static final String ATTRIBUTE_EDITABLE_POINT = "editable point";

	static final String ATTRIBUTE_OBJECT_INSTANCE_NUMBER = "object instance number";
	static final String ATTRIBUTE_USE_COV = "use COV";
	static final String ATTRIBUTE_SETTABLE = "settable";
	static final String ATTRIBUTE_DEFAULT_PRIORITY = "default priority";

	protected BacnetConn conn;
	protected Node node;

	public EditableFolder(BacnetConn conn, Node node) {
		this.conn = conn;
		this.node = node;

		node.setAttribute("restore type", new Value("editable folder"));

		// create the minimum action list
		setEditAction();
		setRemoveAction();
		setAddObjectAction();
		setAddFolderAction();
		setMakeCopyAction();

	}

	protected abstract void addObject(String name, ObjectType type, ActionResult event);

	protected abstract void edit(ActionResult event);

	protected abstract void addFolder(String name);

	protected abstract void setEditAction();

	protected class AddFolderHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			String name = event.getParameter(ATTRIBUTE_NAME, ValueType.STRING).getString();
			node.createChild(name, true).build();
			addFolder(name);
		}
	}

	protected class AddObjectHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			String name = event.getParameter(ATTRIBUTE_NAME, ValueType.STRING).getString();
			ObjectType objectType = Utils.parseObjectType(event.getParameter(ATTRIBUTE_OBJECT_TYPE).getString());

			addObject(name, objectType, event);
		}
	}

	protected class EditHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			edit(event);
		}
	}

	protected class CopyHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			String newname = event.getParameter(ATTRIBUTE_NAME, ValueType.STRING).getString();
			if (newname.length() > 0 && !newname.equals(node.getName()))
				duplicate(newname);
		}
	}

	private void duplicate(String newname) {
		if (newname != null && newname.length() > 0 && !newname.equals(node.getName())) {
			Node parent = node.getParent();
			parent.removeChild(node, false);
			node = parent.createChild(newname, true).build();
		}
	}

	protected class RemoveHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			remove();
		}
	}

	protected void remove() {
		node.clearChildren();
		node.getParent().removeChild(node, false);
	}

	private void setRemoveAction() {
		Action act = new Action(Permission.READ, new RemoveHandler());
		node.createChild("remove", true).setAction(act).build().setSerializable(false);

	}

	private void setAddObjectAction() {
		Action act = new Action(Permission.READ, new AddObjectHandler());
		act.addParameter(new Parameter(ATTRIBUTE_NAME, ValueType.STRING));
		act.addParameter(new Parameter(ATTRIBUTE_OBJECT_TYPE, ValueType.makeEnum(Utils.enumeratedObjectTypeNames())));
		act.addParameter(new Parameter(ATTRIBUTE_OBJECT_INSTANCE_NUMBER, ValueType.NUMBER, new Value(0)));
		act.addParameter(new Parameter(ATTRIBUTE_USE_COV, ValueType.BOOL, new Value(false)));
		act.addParameter(new Parameter(ATTRIBUTE_SETTABLE, ValueType.BOOL, new Value(false)));
		node.createChild(ACTION_ADD_OBJECT, true).setAction(act).build().setSerializable(false);
	}

	private void setAddFolderAction() {

		Action act = new Action(Permission.READ, new AddFolderHandler());
		act.addParameter(new Parameter("name", ValueType.STRING));
		node.createChild("add folder", true).setAction(act).build().setSerializable(false);

	}

	private void setMakeCopyAction() {

		Action act = new Action(Permission.READ, new CopyHandler());
		act.addParameter(new Parameter("name", ValueType.STRING));
		node.createChild("make copy", true).setAction(act).build().setSerializable(false);

	}

	protected abstract LocalDevice getLocalDevice();

	protected abstract EditableFolder getRoot();

	protected abstract BacnetConn getConnection();

	public abstract void restoreLastSession();
}
