package bacnet;

import java.util.Map;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.actions.Parameter;

import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValueType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.serotonin.bacnet4j.LocalDevice;
import com.serotonin.bacnet4j.exception.BACnetException;
import com.serotonin.bacnet4j.obj.BACnetObject;
import com.serotonin.bacnet4j.type.AmbiguousValue;
import com.serotonin.bacnet4j.type.Encodable;
import com.serotonin.bacnet4j.type.enumerated.ObjectType;
import com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier;
import com.serotonin.bacnet4j.type.primitive.CharacterString;
import com.serotonin.bacnet4j.type.primitive.Enumerated;

import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;
import com.serotonin.bacnet4j.type.primitive.OctetString;
import com.serotonin.bacnet4j.type.primitive.Primitive;
import com.serotonin.bacnet4j.type.primitive.Real;
import com.serotonin.bacnet4j.type.primitive.SignedInteger;
import com.serotonin.bacnet4j.type.primitive.UnsignedInteger;
import com.serotonin.bacnet4j.util.PropertyReferences;
import com.serotonin.bacnet4j.util.PropertyValues;

public class LocalDeviceFolder extends EditableFolder {
	private static final Logger LOGGER;

	static {
		LOGGER = LoggerFactory.getLogger(LocalDeviceFolder.class);
	}

	LocalDeviceFolder root;

	public LocalDeviceFolder(BacnetConn conn, Node node) {
		super(conn, node);
	}

	public LocalDeviceFolder(BacnetConn conn, LocalDeviceFolder root, Node node) {
		this(conn, node);

		this.root = root;
	}

	protected void remove() {
		super.remove();
	}

	protected BacnetConn getConnection() {
		return this.conn;
	}

	protected LocalDeviceFolder getRoot() {
		return this.root;
	}

	protected LocalDevice getLocalDevice() {
		return this.root.getLocalDevice();
	}

	void getProperties(PropertyReferences refs, final Map<ObjectIdentifier, BacnetPoint> points) {
		if (root.getLocalDevice() == null)
			return;

	}

	void handleAmbiguous(Encodable enc, LocalBacnetPoint pt, PropertyIdentifier pid) {
		Primitive primitive;
		if (enc instanceof Primitive) {
			primitive = (Primitive) enc;
		} else {
			try {
				AmbiguousValue av = (AmbiguousValue) enc;
				primitive = av.convertTo(Primitive.class);
			} catch (BACnetException e) {
				pt.setPresentValue(e.getMessage(), pid);
				return;
			}
		}
		pt.setPresentValue(PropertyValues.getString(primitive), pid);

		if (primitive instanceof com.serotonin.bacnet4j.type.primitive.Boolean)
			pt.setDataType(DataType.BINARY);
		else if (primitive instanceof SignedInteger || primitive instanceof Real
				|| primitive instanceof com.serotonin.bacnet4j.type.primitive.Double)
			pt.setDataType(DataType.NUMERIC);
		else if (primitive instanceof OctetString || primitive instanceof CharacterString)
			pt.setDataType(DataType.ALPHANUMERIC);
		else if (primitive instanceof Enumerated || primitive instanceof UnsignedInteger)
			pt.setDataType(DataType.MULTISTATE);
	}

	@Override
	protected void addObject(String name, ObjectType objectType, ActionResult event) {

		int instNum = event.getParameter(ATTRIBUTE_OBJECT_INSTANCE_NUMBER, ValueType.NUMBER).getNumber().intValue();
		boolean cov = event.getParameter(ATTRIBUTE_USE_COV, ValueType.BOOL).getBool();
		boolean settable = event.getParameter(ATTRIBUTE_SETTABLE, ValueType.BOOL).getBool();

		Node pointNode = node.createChild(name, true).build();
		pointNode.setAttribute(ATTRIBUTE_OBJECT_TYPE, new Value(objectType.toString()));
		pointNode.setAttribute(ATTRIBUTE_OBJECT_INSTANCE_NUMBER, new Value(instNum));
		pointNode.setAttribute(ATTRIBUTE_USE_COV, new Value(cov));
		pointNode.setAttribute(ATTRIBUTE_SETTABLE, new Value(settable));
		pointNode.setAttribute(ATTRIBUTE_RESTORE_TYPE, new Value(ATTRIBUTE_EDITABLE_POINT));

		LocalBacnetPoint bacnetPoint = new LocalBacnetPoint(this, node, pointNode);
		BACnetObject bacnetObj = bacnetPoint.getBacnetObj();
		Map<BACnetObject, EditablePoint> ObjectToPoint = conn.getObjectToPoint();
		ObjectToPoint.put(bacnetObj, bacnetPoint);

	}

	@Override
	protected void edit(ActionResult event) {
		// TODO Auto-generated method stub

	}

	@Override
	protected void addFolder(String name) {
		Node child = node.createChild(name, true).build();
		new LocalDeviceFolder(conn, root, child);
	}

	@Override
	protected void setEditAction() {
		Action act = new Action(Permission.READ, new EditHandler());

		act.addParameter(new Parameter(ATTRIBUTE_NAME, ValueType.STRING, new Value(node.getName())));

		Node editNode = node.getChild(ACTION_EDIT, true);
		if (editNode == null)
			node.createChild(ACTION_EDIT, true).setAction(act).build().setSerializable(false);
		else
			editNode.setAction(act);
	}

	public void restoreLastSession() {
		restoreLastSession(this.node);
	}

	private void restoreLastSession(Node node) {
		if (node.getChildren() == null)
			return;

		for (Node child : node.getChildren().values()) {
			Value resType = child.getAttribute(ATTRIBUTE_RESTORE_TYPE);
			if (resType != null && ATTRIBUTE_EDITABLE_FOLDER.equals(resType.getString())) {
				LocalDeviceFolder localFolder = new LocalDeviceFolder(conn, this.getRoot(), child);
				localFolder.restoreLastSession(child);
			} else if (resType != null && ATTRIBUTE_EDITABLE_POINT.equals(resType.getString())) {
				Value ot = child.getAttribute(ATTRIBUTE_OBJECT_TYPE);
				Value inum = child.getAttribute(ATTRIBUTE_OBJECT_INSTANCE_NUMBER);
				Value defp = child.getAttribute(ATTRIBUTE_DEFAULT_PRIORITY);
				if (defp == null)
					child.setAttribute(ATTRIBUTE_DEFAULT_PRIORITY, new Value(8));
				if (ot != null && inum != null) {
					LocalBacnetPoint point = new LocalBacnetPoint(this, node, child);
					point.restoreLastSession();
				} else {
					node.removeChild(child);
				}
			} else if (child.getAction() == null && child != root.getStatusNode()) {
				node.removeChild(child);
			}
		}
	}

	public Node getStatusNode() {
		return null;
	}

}
