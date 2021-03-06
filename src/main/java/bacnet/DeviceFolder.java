package bacnet;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.StringUtils;
import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.actions.Parameter;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.util.handler.Handler;
import org.dsa.iot.dslink.util.json.JsonArray;
import org.dsa.iot.dslink.util.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.serotonin.bacnet4j.RemoteDevice;
import com.serotonin.bacnet4j.event.DeviceEventAdapter;
import com.serotonin.bacnet4j.exception.BACnetException;
import com.serotonin.bacnet4j.service.confirmed.SubscribeCOVRequest;
import com.serotonin.bacnet4j.type.AmbiguousValue;
import com.serotonin.bacnet4j.type.Encodable;
import com.serotonin.bacnet4j.type.constructed.BACnetError;
import com.serotonin.bacnet4j.type.constructed.CalendarEntry;
import com.serotonin.bacnet4j.type.constructed.DailySchedule;
import com.serotonin.bacnet4j.type.constructed.DateRange;
import com.serotonin.bacnet4j.type.constructed.DateTime;
import com.serotonin.bacnet4j.type.constructed.Destination;
import com.serotonin.bacnet4j.type.constructed.DeviceObjectPropertyReference;
import com.serotonin.bacnet4j.type.constructed.EventTransitionBits;
import com.serotonin.bacnet4j.type.constructed.PropertyValue;
import com.serotonin.bacnet4j.type.constructed.SequenceOf;
import com.serotonin.bacnet4j.type.constructed.SpecialEvent;
import com.serotonin.bacnet4j.type.constructed.TimeValue;
import com.serotonin.bacnet4j.type.enumerated.ObjectType;
import com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier;
import com.serotonin.bacnet4j.type.enumerated.EngineeringUnits;
import com.serotonin.bacnet4j.type.primitive.CharacterString;
import com.serotonin.bacnet4j.type.primitive.Enumerated;
import com.serotonin.bacnet4j.type.primitive.Null;
import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;
import com.serotonin.bacnet4j.type.primitive.OctetString;
import com.serotonin.bacnet4j.type.primitive.Primitive;
import com.serotonin.bacnet4j.type.primitive.Real;
import com.serotonin.bacnet4j.type.primitive.SignedInteger;
import com.serotonin.bacnet4j.type.primitive.UnsignedInteger;
import com.serotonin.bacnet4j.util.PropertyReferences;
import com.serotonin.bacnet4j.util.PropertyValues;
import com.serotonin.bacnet4j.util.RequestListener;
import com.serotonin.bacnet4j.util.RequestUtils;

public class DeviceFolder {
	private static final Logger LOGGER;

	protected Node node;
	protected BacnetConn conn;
	protected DeviceFolder root;
	private int unnamedCount;
	final Set<ObjectIdentifier> pointSet = new HashSet<ObjectIdentifier>();

	static {
		LOGGER = LoggerFactory.getLogger(DeviceFolder.class);
	}

	DeviceFolder(BacnetConn conn, Node node) {
		this.conn = conn;
		this.node = node;

		node.setAttribute("restore type", new Value("folder"));

		Action act = new Action(Permission.READ, new RemoveHandler());
		node.createChild("remove", true).setAction(act).build().setSerializable(false);

		act = new Action(Permission.READ, new AddFolderHandler());
		act.addParameter(new Parameter("name", ValueType.STRING));
		node.createChild("add folder", true).setAction(act).build().setSerializable(false);

		act = new Action(Permission.READ, new ObjectDiscoveryHandler());
		node.createChild("discover objects", true).setAction(act).build().setSerializable(false);

		act = new Action(Permission.READ, new AddObjectHandler());
		act.addParameter(new Parameter("name", ValueType.STRING));
		act.addParameter(new Parameter("object type", ValueType.makeEnum(Utils.enumeratedObjectTypeNames())));
		act.addParameter(new Parameter("object instance number", ValueType.NUMBER, new Value(0)));
		act.addParameter(new Parameter("use COV", ValueType.BOOL, new Value(false)));
		act.addParameter(new Parameter("settable", ValueType.BOOL, new Value(false)));
		act.addParameter(new Parameter("default priority", ValueType.NUMBER, new Value(8)));
		node.createChild("add object", true).setAction(act).build().setSerializable(false);

		act = new Action(Permission.READ, new CopyHandler());
		act.addParameter(new Parameter("name", ValueType.STRING));
		node.createChild("make copy", true).setAction(act).build().setSerializable(false);

		act = new Action(Permission.READ, new RenameHandler());
		act.addParameter(new Parameter("name", ValueType.STRING, new Value(node.getName())));
		node.createChild("edit", true).setAction(act).build().setSerializable(false);

	}

	DeviceFolder(BacnetConn conn, Node node, DeviceFolder root) {
		this(conn, node);
		this.root = root;
	}

	public void restoreLastSession() {
		if (node.getChildren() == null)
			return;
		for (Node child : node.getChildren().values()) {
			Value restype = child.getAttribute("restore type");
			if (restype != null && restype.getString().equals("folder")) {
				DeviceFolder df = new DeviceFolder(conn, child, root);
				df.restoreLastSession();
			} else if (restype != null && restype.getString().equals("point")) {
				Value ot = child.getAttribute("object type");
				Value inum = child.getAttribute("object instance number");
				Value cov = child.getAttribute("use COV");
				Value sett = child.getAttribute("settable");
				Value defp = child.getAttribute("default priority");
				if (defp == null)
					child.setAttribute("default priority", new Value(8));
				if (ot != null && inum != null && cov != null && sett != null) {
					new BacnetPoint(this, node, child);
				} else {
					node.removeChild(child, false);
				}
			} else if (child.getAction() == null && child != root.getStatusNode() && child != root.getEventNode()) {
				node.removeChild(child, false);
			}
		}
	}

	private class AddObjectHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			String name = event.getParameter("name", ValueType.STRING).getString();
			ObjectType ot = Utils.parseObjectType(event.getParameter("object type").getString());
			int instNum = event.getParameter("object instance number", ValueType.NUMBER).getNumber().intValue();
			boolean cov = event.getParameter("use COV", ValueType.BOOL).getBool();
			boolean sett = event.getParameter("settable", ValueType.BOOL).getBool();
			int defprio = event.getParameter("default priority", ValueType.NUMBER).getNumber().intValue();

			Node pnode = node.createChild(name, true).build();
			pnode.setAttribute("object type", new Value(ot.toString()));
			pnode.setAttribute("object instance number", new Value(instNum));
			pnode.setAttribute("use COV", new Value(cov));
			pnode.setAttribute("settable", new Value(sett));
			pnode.setAttribute("restore type", new Value("point"));
			pnode.setAttribute("default priority", new Value(defprio));

			new BacnetPoint(DeviceFolder.this, node, pnode);
		}
	}

	protected class ObjectDiscoveryHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			if (root.getRemoteDevice() == null)
				return;

			final PropertyReferences refs = new PropertyReferences();
			final Map<ObjectIdentifier, BacnetPoint> points = new HashMap<ObjectIdentifier, BacnetPoint>();
			try {
				RequestUtils.sendReadPropertyAllowNull(root.conn.localDevice, root.getRemoteDevice(),
						root.getRemoteDevice().getObjectIdentifier(), PropertyIdentifier.objectList, null,
						new RequestListener() {
							public boolean requestProgress(double prog, ObjectIdentifier oidin, PropertyIdentifier pid,
									UnsignedInteger pin, Encodable value) {
								if (pin == null) {
									for (Object o : (SequenceOf<?>) value) {
										ObjectIdentifier oid = (ObjectIdentifier) o;
										// LOGGER.info(oid.getObjectType().toString());
										if (!pointSet.contains(oid)) {
											pointSet.add(oid);
											addObjectPoint(oid, refs, points);
										}
									}
								} else {
									ObjectIdentifier oid = (ObjectIdentifier) value;
									// LOGGER.info(oid.getObjectType().toString());
									if (!pointSet.contains(oid)) {
										addObjectPoint(oid, refs, points);
									}

								}
								return false;
							}
						});
			} catch (BACnetException e) {
				// TODO Auto-generated catch block
				// e.printStackTrace();
				LOGGER.debug("error: ", e.getMessage());
			}

			getProperties(refs, points);
		}
	}

	void setupCov(final BacnetPoint point, DeviceEventAdapter listener) {
		if (root.getRemoteDevice() == null)
			return;
		CovType ct = CovType.NONE;
		try {
			ct = CovType.valueOf(root.node.getAttribute("cov usage").getString());
		} catch (Exception e) {
		}
		if (ct == CovType.NONE)
			return;
		final com.serotonin.bacnet4j.type.primitive.Boolean confirmed = new com.serotonin.bacnet4j.type.primitive.Boolean(
				ct == CovType.CONFIRMED);
		final UnsignedInteger lifetime = new UnsignedInteger(
				60 * root.node.getAttribute("cov lease time (minutes)").getNumber().intValue());
		final UnsignedInteger id = new UnsignedInteger(point.id);
		conn.localDevice.getEventHandler().addListener(listener);

		root.getDaemonThreadPool().schedule(new Runnable() {
			public void run() {
				try {
					conn.localDevice.send(root.getRemoteDevice(),
							new SubscribeCOVRequest(id, point.oid, confirmed, lifetime));
				} catch (Exception e) {
					LOGGER.debug("error: ", e);
				}
			}
		}, 0, TimeUnit.SECONDS);
	}

	public class CovListener extends DeviceEventAdapter {

		ArrayBlockingQueue<SequenceOf<PropertyValue>> event;
		BacnetPoint point;

		CovListener(BacnetPoint p) {
			this.point = p;
			this.event = new ArrayBlockingQueue<SequenceOf<PropertyValue>>(1);
		}

		@Override
		public void covNotificationReceived(final UnsignedInteger subscriberProcessIdentifier,
				final RemoteDevice initiatingDevice, final ObjectIdentifier monitoredObjectIdentifier,
				final UnsignedInteger timeRemaining, final SequenceOf<PropertyValue> listOfValues) {
			if (root.getRemoteDevice() != null && root.getRemoteDevice().equals(initiatingDevice)
					&& point.oid.equals(monitoredObjectIdentifier)) {
				event.clear();
				event.add(listOfValues);
			}
		}
	}

	// class CovEvent {
	// private BacnetPoint point;
	// private SequenceOf<PropertyValue> listOfValues;
	// //boolean active;
	// CovEvent(BacnetPoint pt) {
	// point = pt;
	// listOfValues = null;
	// //active = true;
	// }
	// void update(SequenceOf<PropertyValue> lov) {
	// listOfValues = lov;
	// }
	//
	// void process() {
	// if (listOfValues == null) return;
	// for (PropertyValue pv: listOfValues) {
	// if (point.node != null) LOGGER.debug("got cov for " +
	// point.node.getName());
	// updatePointValue(point, pv.getPropertyIdentifier(), pv.getValue());
	// }
	// listOfValues = null;
	// }
	// }

	void getProperties(PropertyReferences refs, final Map<ObjectIdentifier, BacnetPoint> points) {
		if (root.getRemoteDevice() == null)
			return;
		try {
			RequestUtils.readProperties(root.conn.localDevice, root.getRemoteDevice(), refs, new RequestListener() {

				public boolean requestProgress(double prog, ObjectIdentifier oid, PropertyIdentifier pid,
						UnsignedInteger unsignedinteger, Encodable encodable) {
					BacnetPoint pt = points.get(oid);

					try {
						updatePointValue(pt, pid, encodable);
					} catch (Exception e) {
						LOGGER.debug("", e);
					}

					return prog == 1;
				}
			});
		} catch (BACnetException e) {
			// TODO Auto-generated catch block
			// e.printStackTrace();
			LOGGER.debug("error: ", e);
		}
	}

	void updatePointValue(BacnetPoint pt, PropertyIdentifier pid, Encodable encodable) {
		if (encodable instanceof BACnetError)
			return;
		if (pid.equals(PropertyIdentifier.objectName)) {
			String name = PropertyValues.getString(encodable);
			if (name.length() < 1) {
				pt.setObjectName("unnamed device " + unnamedCount);
				unnamedCount += 1;
			} else {
				pt.setObjectName(name);
			}
		} else
			if (pid.equals(PropertyIdentifier.presentValue) && ObjectType.schedule.intValue() == pt.getObjectTypeId()) {
			handleAmbiguous(encodable, pt, pid);
		} else if (pid.equals(PropertyIdentifier.presentValue)) {
			pt.setPresentValue(PropertyValues.getString(encodable), pid);
		} else if (pid.equals(PropertyIdentifier.modelName)) {
			pt.setPresentValue(PropertyValues.getString(encodable), pid);
		} else if (pid.equals(PropertyIdentifier.units)) {
			String eu = ("engUnit.abbr." + ((EngineeringUnits) encodable).intValue());
			pt.setEngineeringUnits(eu);
			pt.getUnitsDescription().add(PropertyValues.getString(encodable));
		} else if (pid.equals(PropertyIdentifier.inactiveText)) {
			Encodable e = PropertyValues.getNullOnError(encodable);
			String s = "0";
			if (e != null && !StringUtils.isEmpty(e.toString()))
				s = e.toString();
			pt.getUnitsDescription().set(0, s);
		} else if (pid.equals(PropertyIdentifier.activeText)) {
			Encodable e = PropertyValues.getNullOnError(encodable);
			String s = "1";
			if (e != null && !StringUtils.isEmpty(e.toString()))
				s = e.toString();
			pt.getUnitsDescription().set(1, s);
		} else if (pid.equals(PropertyIdentifier.outputUnits)) {
			String eu = ("engUnit.abbr." + ((EngineeringUnits) encodable).intValue());
			pt.setEngineeringUnits(eu);
			pt.getUnitsDescription().add(PropertyValues.getString(encodable));
		} else if (pid.equals(PropertyIdentifier.stateText)) {
			@SuppressWarnings("unchecked")
			SequenceOf<CharacterString> states = (SequenceOf<CharacterString>) encodable;
			for (CharacterString state : states)
				pt.getUnitsDescription().add(state.toString());
		} else if (pid.equals(PropertyIdentifier.modelName)) {
			pt.setPresentValue(PropertyValues.getString(encodable), pid);
		} else if (pid.equals(PropertyIdentifier.logDeviceObjectProperty)
				&& encodable instanceof DeviceObjectPropertyReference) {
			DeviceObjectPropertyReference ref = (DeviceObjectPropertyReference) encodable;
			if (ref.getDeviceIdentifier() != null)
				pt.setReferenceDevice(ref.getDeviceIdentifier().toString());
			else if (root.getRemoteDevice() != null)
				pt.setReferenceDevice(root.getRemoteDevice().getObjectIdentifier().toString());
			if (ref.getObjectIdentifier() != null) {
				pt.setReferenceObject(ref.getObjectIdentifier().toString());
				pt.setDataType(Utils.getDataType(ref.getObjectIdentifier().getObjectType()));
			}
			if (ref.getPropertyIdentifier() != null)
				pt.setReferenceProperty(ref.getPropertyIdentifier().toString());
		} else if (pid.equals(PropertyIdentifier.recordCount)) {
			// pt.setPresentValue(PropertyValues.getString(encodable), pid);
			pt.setRecordCount(((UnsignedInteger) encodable).intValue());
		} else if (pid.equals(PropertyIdentifier.bufferSize)) {
			pt.setBufferSize(((UnsignedInteger) encodable).intValue());
		} else if (pid.equals(PropertyIdentifier.startTime)) {
			pt.setStartTime(Utils.datetimeToString((DateTime) encodable));
		} else if (pid.equals(PropertyIdentifier.stopTime)) {
			pt.setStopTime(Utils.datetimeToString((DateTime) encodable));
		} else if (pid.equals(PropertyIdentifier.logBuffer)) {
			pt.setLogBuffer(PropertyValues.getString(encodable));
		} else if (pid.equals(PropertyIdentifier.effectivePeriod)) {
			DateRange dr = (DateRange) encodable;
			pt.setEffectivePeriod(Utils.dateToString(dr.getStartDate()) + " - " + Utils.dateToString(dr.getEndDate()));
		} else if (pid.equals(PropertyIdentifier.weeklySchedule)) {
			JsonArray jarr = new JsonArray();
			for (DailySchedule ds : (SequenceOf<DailySchedule>) encodable) {
				JsonArray darr = new JsonArray();
				for (TimeValue tv : ds.getDaySchedule()) {
					darr.add(Utils.timeValueToJson(tv));
					byte ti = Utils.getPrimitiveType(tv.getValue());
					if (ti != Null.TYPE_ID)
						pt.typeid = ti;
				}
				jarr.add(darr);
			}
			pt.setWeeklySchedule(jarr);
		} else if (pid.equals(PropertyIdentifier.exceptionSchedule)) {
			JsonArray jarr = new JsonArray();
			for (SpecialEvent se : (SequenceOf<SpecialEvent>) encodable) {
				jarr.add(Utils.specialEventToJson(se));
			}
			pt.setExceptionSchedule(jarr); // TODO
		} else if (pid.equals(PropertyIdentifier.notificationClass)) {
			pt.setPresentValue(PropertyValues.getString(encodable), pid);
		} else if (pid.equals(PropertyIdentifier.priority)) {
			JsonArray jarr = new JsonArray();
			for (UnsignedInteger n : (SequenceOf<UnsignedInteger>) encodable) {
				jarr.add(n.intValue());
			}
			pt.setPriority(jarr);
		} else if (pid.equals(PropertyIdentifier.ackRequired)) {
			JsonArray jarr = new JsonArray();
			for (int i = 1; i < 4; i++) {
				jarr.add(((EventTransitionBits) encodable).getValue(i));
			}
			pt.setAckRequired(jarr);
		} else if (pid.equals(PropertyIdentifier.recipientList)) {
			JsonArray jarr = new JsonArray();
			for (Destination dest : (SequenceOf<Destination>) encodable) {
				jarr.add(Utils.destinationToJson(dest));
			}
			pt.setRecipientList(jarr);
		} else if (pid.equals(PropertyIdentifier.dateList)) {
			JsonArray jarr = new JsonArray();
			for (CalendarEntry ce : (SequenceOf<CalendarEntry>) encodable) {
				jarr.add(Utils.calendarEntryToJson(ce));
			}
			pt.setDateList(jarr);
		}
		pt.update();
	}

	void addObjectPoint(ObjectIdentifier oid, PropertyReferences refs, Map<ObjectIdentifier, BacnetPoint> points) {
		addPropertyReferences(refs, oid);

		BacnetPoint pt = new BacnetPoint(this, node, oid);

		boolean defaultSettable = Utils.isOneOf(oid.getObjectType(), ObjectType.analogOutput, ObjectType.analogValue,
				ObjectType.binaryOutput, ObjectType.binaryValue, ObjectType.multiStateOutput,
				ObjectType.multiStateValue);
		pt.setSettable(defaultSettable);

		points.put(oid, pt);
	}

	static void addPropertyReferences(PropertyReferences refs, ObjectIdentifier oid) {
		refs.add(oid, PropertyIdentifier.objectName);

		ObjectType type = oid.getObjectType();
		if (Utils.isOneOf(type, ObjectType.accumulator)) {
			refs.add(oid, PropertyIdentifier.units);
			refs.add(oid, PropertyIdentifier.presentValue);
		} else if (Utils.isOneOf(type, ObjectType.analogInput, ObjectType.analogOutput, ObjectType.analogValue,
				ObjectType.pulseConverter)) {
			refs.add(oid, PropertyIdentifier.units);
			refs.add(oid, PropertyIdentifier.presentValue);
		} else if (Utils.isOneOf(type, ObjectType.binaryInput, ObjectType.binaryOutput, ObjectType.binaryValue)) {
			refs.add(oid, PropertyIdentifier.inactiveText);
			refs.add(oid, PropertyIdentifier.activeText);
			refs.add(oid, PropertyIdentifier.presentValue);
		} else if (Utils.isOneOf(type, ObjectType.device)) {
			refs.add(oid, PropertyIdentifier.modelName);
		} else if (Utils.isOneOf(type, ObjectType.lifeSafetyPoint)) {
			refs.add(oid, PropertyIdentifier.units);
			refs.add(oid, PropertyIdentifier.presentValue);
		} else if (Utils.isOneOf(type, ObjectType.loop)) {
			refs.add(oid, PropertyIdentifier.outputUnits);
			refs.add(oid, PropertyIdentifier.presentValue);
		} else if (Utils.isOneOf(type, ObjectType.multiStateInput, ObjectType.multiStateOutput,
				ObjectType.multiStateValue)) {
			refs.add(oid, PropertyIdentifier.stateText);
			refs.add(oid, PropertyIdentifier.presentValue);
		} else if (Utils.isOneOf(type, ObjectType.schedule)) {
			refs.add(oid, PropertyIdentifier.presentValue);
			refs.add(oid, PropertyIdentifier.effectivePeriod);
			refs.add(oid, PropertyIdentifier.weeklySchedule);
			refs.add(oid, PropertyIdentifier.exceptionSchedule);
		} else if (Utils.isOneOf(type, ObjectType.trendLog)) {
			refs.add(oid, PropertyIdentifier.logDeviceObjectProperty);
			refs.add(oid, PropertyIdentifier.recordCount);
			refs.add(oid, PropertyIdentifier.startTime);
			refs.add(oid, PropertyIdentifier.stopTime);
			refs.add(oid, PropertyIdentifier.bufferSize);
			// refs.add(oid, PropertyIdentifier.logBuffer);
		} else if (Utils.isOneOf(type, ObjectType.notificationClass)) {
			refs.add(oid, PropertyIdentifier.notificationClass);
			refs.add(oid, PropertyIdentifier.priority);
			refs.add(oid, PropertyIdentifier.ackRequired);
			refs.add(oid, PropertyIdentifier.recipientList);
		} else if (Utils.isOneOf(type, ObjectType.calendar)) {
			refs.add(oid, PropertyIdentifier.presentValue);
			refs.add(oid, PropertyIdentifier.dateList);
		}
	}

	void handleAmbiguous(Encodable enc, BacnetPoint pt, PropertyIdentifier pid) {
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

	protected class RemoveHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			remove();
		}
	}

	protected class CopyHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			String newname = event.getParameter("name", ValueType.STRING).getString();
			if (newname.length() > 0 && !newname.equals(node.getName()))
				duplicate(newname);
		}
	}

	protected class RenameHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			String newname = event.getParameter("name", ValueType.STRING).getString();
			if (newname.length() > 0 && !newname.equals(node.getName()))
				rename(newname);
		}
	}

	protected void remove() {
		node.clearChildren();
		node.getParent().removeChild(node, false);
	}

	protected void rename(String newname) {
		duplicate(newname);
		remove();
	}

	protected void duplicate(String name) {
		JsonObject jobj = conn.link.copySerializer.serialize();
		JsonObject parentobj = getParentJson(jobj, node);
		JsonObject nodeobj = parentobj.get(node.getName());
		parentobj.put(org.dsa.iot.dslink.util.StringUtils.encodeName(name), nodeobj);
		conn.link.copyDeserializer.deserialize(jobj);
		Node newnode = node.getParent().getChild(name, true);
		DeviceFolder df = new DeviceFolder(conn, newnode, root);
		df.restoreLastSession();
	}

	protected JsonObject getParentJson(JsonObject jobj, Node n) {
		if (n == root.node)
			return root.getParentJson(jobj, n);
		else
			return getParentJson(jobj, n.getParent()).get(n.getParent().getName());
	}

	protected class AddFolderHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			String name = event.getParameter("name", ValueType.STRING).getString();
			Node child = node.createChild(name, true).build();
			new DeviceFolder(conn, child, root);
		}
	}

	/*
	 * Template method. Derived class returns remote device object.
	 */
	public RemoteDevice getRemoteDevice() {
		return null;
	}

	/*
	 * Template method. Derived class returns event node.
	 */
	public Node getEventNode() {
		return null;
	}

	/*
	 * Template method. Derived class returns status node.
	 */
	public Node getStatusNode() {
		return null;
	}

	/*
	 * Template method. Derived class returns a thread pool.
	 */
	public ScheduledThreadPoolExecutor getDaemonThreadPool() {
		return null;
	}

	void addPointSub(BacnetPoint point) {
	}

	void removePointSub(BacnetPoint point) {
	}

	/*
	 * Template method. Derived class returns CoV type.
	 */
	CovType getCovType() {
		return null;
	}
}
