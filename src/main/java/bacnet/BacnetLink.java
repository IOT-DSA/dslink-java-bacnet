package bacnet;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.regex.Pattern;

import jssc.SerialNativeInterface;
import jssc.SerialPortList;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.actions.Parameter;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.serializer.Deserializer;
import org.dsa.iot.dslink.serializer.Serializer;
import org.dsa.iot.dslink.util.handler.Handler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.serotonin.bacnet4j.npdu.ip.IpNetwork;
import com.serotonin.io.serial.CommPortConfigException;
import com.serotonin.io.serial.CommPortIdentifier;
import com.serotonin.io.serial.CommPortProxy;

public class BacnetLink {
	private static final Logger LOGGER;

	private Node node;
	final Map<BacnetPoint, ScheduledFuture<?>> futures;
	final Set<BacnetConn> connections;
	Serializer copySerializer;
	Deserializer copyDeserializer;

	static final String ATTRIBUTE_BBMD_IP_WITH_NETWORK_NUMBER = "bbmd ips with network number";

	static {
		LOGGER = LoggerFactory.getLogger(BacnetLink.class);
	}

	private BacnetLink(Node node, Serializer ser, Deserializer deser) {
		this.node = node;
		this.futures = new ConcurrentHashMap<BacnetPoint, ScheduledFuture<?>>();
		this.copyDeserializer = deser;
		this.copySerializer = ser;
		this.connections = new HashSet<BacnetConn>();
	}

	public static void start(Node parent, Serializer ser, Deserializer deser) {
		Node node = parent;
		final BacnetLink link = new BacnetLink(node, ser, deser);
		link.init();
	}

	private void init() {

		restoreLastSession();

		Action act = getAddIpConnAction();
		node.createChild("add ip connection", true).setAction(act).build().setSerializable(false);

		// act = new Action(Permission.READ, new RxtxSetupHandler());
		// act.addParameter(new Parameter("Operating System",
		// ValueType.makeEnum("Windows-x32", "Windows-x64", "Linux-x86",
		// "Linux-x86_64", "Linux-ia64", "MacOSX")));
		// node.createChild("setup
		// rxtx").setAction(act).build().setSerializable(false);

		act = getAddSerialAction();
		node.createChild("add mstp connection", true).setAction(act).build().setSerializable(false);

		act = new Action(Permission.READ, new PortScanHandler());
		node.createChild("scan for serial ports", true).setAction(act).build().setSerializable(false);
	}

	private class PortScanHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			LOGGER.debug("port scan invoked");
			Action act = getAddSerialAction();
			Node anode = node.getChild("add mstp connection", true);
			if (anode == null) {
				anode = node.createChild("add mstp connection", true).setAction(act).build();
				anode.setSerializable(false);
			} else {
				anode.setAction(act);
			}

			for (BacnetConn conn : connections) {
				anode = conn.node.getChild("edit", true);
				if (anode != null) {
					act = conn.getEditAction();
					anode.setAction(act);
				}
			}
		}
	}

	static Set<String> listPorts() {
		Set<String> portids = new HashSet<String>();
		try {
			List<CommPortProxy> cports = getCommPorts();
			for (CommPortProxy port : cports) {
				portids.add(port.getId());
				LOGGER.debug("comm port found: " + port.getId());
			}
		} catch (CommPortConfigException e) {
			// TODO Auto-generated catch block
			LOGGER.debug("error scanning for ports: ", e);
		}
		return portids;
	}

	private static List<CommPortProxy> getCommPorts() throws CommPortConfigException {
		try {
			List<CommPortProxy> ports = new LinkedList<CommPortProxy>();
			String[] portNames;

			switch (SerialNativeInterface.getOsType()) {
			case SerialNativeInterface.OS_LINUX:
				portNames = SerialPortList
						.getPortNames(Pattern.compile("(cu|ttyS|ttyUSB|ttyACM|ttyAMA|rfcomm|ttyO)[0-9]{1,3}"));
				LOGGER.debug("got linux port names");
				break;
			case SerialNativeInterface.OS_MAC_OS_X:
				portNames = SerialPortList.getPortNames(Pattern.compile("(cu|tty)..*")); // Was
																							// "tty.(serial|usbserial|usbmodem).*")
				LOGGER.debug("got mac port names");
				break;
			default:
				portNames = SerialPortList.getPortNames();
				LOGGER.debug("got default (windows) port names");
				break;
			}

			for (String portName : portNames) {
				CommPortIdentifier id = new CommPortIdentifier(portName, false);
				ports.add(new CommPortProxy(id));
			}

			return ports;
		} catch (UnsatisfiedLinkError e) {
			throw new CommPortConfigException(e.getMessage());
		} catch (NoClassDefFoundError e) {
			throw new CommPortConfigException(
					"Comm configuration error. Check that rxtx DLL or libraries have been correctly installed.");
		}
	}

	private Action getAddIpConnAction() {
		Action act = new Action(Permission.READ, new AddIpConnHandler());
		act.addParameter(new Parameter("name", ValueType.STRING));
		act.addParameter(new Parameter("broadcast ip", ValueType.STRING, new Value(IpNetwork.DEFAULT_BROADCAST_IP)));
		act.addParameter(new Parameter("port", ValueType.NUMBER, new Value(IpNetwork.DEFAULT_PORT)));
		act.addParameter(new Parameter("local bind address", ValueType.STRING, new Value(IpNetwork.DEFAULT_BIND_IP)));
		act.addParameter(new Parameter("local network number", ValueType.NUMBER, new Value(0)));
		act.addParameter(new Parameter("register as foreign device in bbmd", ValueType.BOOL, new Value(false)));
		act.addParameter(new Parameter(ATTRIBUTE_BBMD_IP_WITH_NETWORK_NUMBER, ValueType.STRING));
		act.addParameter(new Parameter("strict device comparisons", ValueType.BOOL, new Value(true)));
		act.addParameter(new Parameter("Timeout", ValueType.NUMBER, new Value(6000)));
		act.addParameter(new Parameter("segment timeout", ValueType.NUMBER, new Value(5000)));
		act.addParameter(new Parameter("segment window", ValueType.NUMBER, new Value(5)));
		act.addParameter(new Parameter("retries", ValueType.NUMBER, new Value(2)));
		act.addParameter(new Parameter("local device id", ValueType.NUMBER, new Value(1212)));
		act.addParameter(new Parameter("local device name", ValueType.STRING, new Value("DSLink")));
		act.addParameter(new Parameter("local device vendor", ValueType.STRING, new Value("DGLogik Inc.")));
		act.addParameter(new Parameter("default polling interval", ValueType.NUMBER, new Value(5)));

		return act;
	}

	private Action getAddSerialAction() {
		Action act = new Action(Permission.READ, new AddSerialConnHandler());
		act.addParameter(new Parameter("name", ValueType.STRING));
		Set<String> portids = listPorts();
		if (portids.size() > 0) {
			act.addParameter(new Parameter("comm port id", ValueType.makeEnum(portids)));
			act.addParameter(new Parameter("comm port id (manual entry)", ValueType.STRING));
		} else {
			act.addParameter(new Parameter("comm port id", ValueType.STRING));
		}
		act.addParameter(new Parameter("baud rate", ValueType.NUMBER, new Value(19200)));
		act.addParameter(new Parameter("this station id", ValueType.NUMBER, new Value(0)));
		act.addParameter(new Parameter("frame error retry count", ValueType.NUMBER, new Value(1)));
		act.addParameter(new Parameter("local network number", ValueType.NUMBER, new Value(0)));
		act.addParameter(new Parameter("strict device comparisons", ValueType.BOOL, new Value(true)));
		act.addParameter(new Parameter("Timeout", ValueType.NUMBER, new Value(6000)));
		act.addParameter(new Parameter("segment timeout", ValueType.NUMBER, new Value(5000)));
		act.addParameter(new Parameter("segment window", ValueType.NUMBER, new Value(5)));
		act.addParameter(new Parameter("retries", ValueType.NUMBER, new Value(2)));
		act.addParameter(new Parameter("local device id", ValueType.NUMBER, new Value(1212)));
		act.addParameter(new Parameter("local device name", ValueType.STRING, new Value("DSLink")));
		act.addParameter(new Parameter("local device vendor", ValueType.STRING, new Value("DGLogik Inc.")));
		act.addParameter(new Parameter("default polling interval", ValueType.NUMBER, new Value(5)));
		return act;
	}

	public void restoreLastSession() {
		if (node.getChildren() == null)
			return;

		for (Node child : node.getChildren().values()) {
			// IP transport
			Value broadcastIp = child.getAttribute("broadcast ip");
			Value port = child.getAttribute("port");
			Value localBindAddress = child.getAttribute("local bind address");
			Value isRegisteredAsFeignDevice = child.getAttribute("register as foreign device in bbmd");
			if (isRegisteredAsFeignDevice == null)
				child.setAttribute("register as foreign device in bbmd", new Value(false));

			Value bbmdIpList = child.getAttribute(ATTRIBUTE_BBMD_IP_WITH_NETWORK_NUMBER);
			if (bbmdIpList == null) {
				Value bbmdIp = child.getAttribute("bbmd ip");
				Value bbmdPort = child.getAttribute("bbmd port");
				if (bbmdIp != null && bbmdPort != null) {
					String iplist = bbmdIp.getString() + ":" + bbmdPort.getNumber().toString();
					child.setAttribute(ATTRIBUTE_BBMD_IP_WITH_NETWORK_NUMBER, new Value(iplist));
				} else {
					child.setAttribute(ATTRIBUTE_BBMD_IP_WITH_NETWORK_NUMBER, new Value(" "));
				}
			}
			// MSTP transport
			Value commPort = child.getAttribute("comm port id");
			Value baud = child.getAttribute("baud rate");
			Value station = child.getAttribute("this station id");
			Value ferc = child.getAttribute("frame error retry count");

			// Common attribution
			Value localNetworkNumber = child.getAttribute("local network number");
			Value strict = child.getAttribute("strict device comparisons");
			Value timeout = child.getAttribute("Timeout");
			Value segmentTimeout = child.getAttribute("segment timeout");
			Value segmentWindow = child.getAttribute("segment window");
			Value retries = child.getAttribute("retries");
			Value localDeviceId = child.getAttribute("local device id");
			Value localDeviceName = child.getAttribute("local device name");
			Value localDeviceVendor = child.getAttribute("local device vendor");
			Value interval = child.getAttribute("default polling interval");
			if (localNetworkNumber != null && strict != null && timeout != null && segmentTimeout != null
					&& segmentWindow != null && retries != null && localDeviceId != null && localDeviceName != null
					&& localDeviceVendor != null && interval != null) {
				BacnetConn connection = null;
				if (broadcastIp != null && port != null && localBindAddress != null) {
					connection = new BacnetIpConnection(getMe(), child);
				} else if (commPort != null && baud != null && station != null && ferc != null) {
					connection = new BacnetSerialConnection(getMe(), child);
				}

				if (connection != null) {
					connection.restoreLastSession();
				}
			} else if (!child.getName().equals("defs")) {
				node.removeChild(child, false);
			}
		}
	}

	void setupPoint(final BacnetPoint point, final DeviceFolder devicefold) {
		if (devicefold.conn.localDevice == null) {
			devicefold.conn.stop();
			return;
		}
		setupNode(point, devicefold, 0);
		setupNode(point, devicefold, 1);
	}

	private void setupNode(final BacnetPoint point, final DeviceFolder folder, final int nodeIndex) {
		LOGGER.debug("setting up node " + nodeIndex + " of point " + point.node.getName());
		final Node child;
		switch (nodeIndex) {
		case 0:
			child = point.node;
			break;
		case 1:
			child = point.node.getChild("present value", true);
			break;
		default:
			return;
		}
		child.getListener().setOnSubscribeHandler(new Handler<Node>() {
			public void handle(final Node event) {
				if (folder.root.getCovType() != CovType.NONE && point.isCov()) {
					LOGGER.debug("subscribed (with cov) to node " + child.getName());
					point.subscribe(nodeIndex, true);
				} else {
					LOGGER.debug("subscribed (without cov) to node " + child.getName());
					point.subscribe(nodeIndex, false);
				}
			}
		});
		child.getListener().setOnUnsubscribeHandler(new Handler<Node>() {
			public void handle(final Node event) {
				LOGGER.debug("unsubscribed from node " + child.getName());
				point.unsubscribe(nodeIndex);
			}
		});
	}

	class AddIpConnHandler extends AddConnHandler {

		@Override
		void addTransportParameters(ActionResult event) {
			broadcastIp = event.getParameter("broadcast ip", ValueType.STRING).getString();
			port = event.getParameter("port", ValueType.NUMBER).getNumber().intValue();
			localBindAddress = event.getParameter("local bind address", ValueType.STRING).getString();

			isRegisteredAsFeignDevice = event.getParameter("register as foreign device in bbmd", ValueType.BOOL)
					.getBool();
			bbmdIpList = event.getParameter(ATTRIBUTE_BBMD_IP_WITH_NETWORK_NUMBER, new Value(" ")).getString();

		}

		@Override
		BacnetConn createBacnetConnection(BacnetLink link, Node node) {
			return new BacnetIpConnection(link, node);
		}

	}

	class AddSerialConnHandler extends AddConnHandler {

		@Override
		void addTransportParameters(ActionResult event) {
			commPort = event.getParameter("comm port id", ValueType.STRING).getString();
			baud = event.getParameter("baud rate", ValueType.NUMBER).getNumber().intValue();
			station = event.getParameter("this station id", ValueType.NUMBER).getNumber().intValue();
			frameErrorRetryCount = event.getParameter("frame error retry count", ValueType.NUMBER).getNumber()
					.intValue();
		}

		@Override
		BacnetConn createBacnetConnection(BacnetLink link, Node node) {
			return new BacnetSerialConnection(link, node);

		}

	}

	abstract class AddConnHandler implements Handler<ActionResult> {
		// ip transport
		String broadcastIp = null;
		int port = 0;
		String localBindAddress = null;
		boolean isRegisteredAsFeignDevice = false;
		String bbmdIpList = " ";

		// mstp transport
		String commPort = null;
		int baud = 0;
		int station = 0;
		int frameErrorRetryCount = 1;

		abstract void addTransportParameters(ActionResult event);

		abstract BacnetConn createBacnetConnection(BacnetLink link, Node node);

		public void handle(ActionResult event) {
			String name = event.getParameter("name", ValueType.STRING).getString();

			addTransportParameters(event);

			// common parameters
			int localNetworkNumber = event.getParameter("local network number", ValueType.NUMBER).getNumber()
					.intValue();
			boolean strict = event.getParameter("strict device comparisons", ValueType.BOOL).getBool();
			int timeout = event.getParameter("Timeout", ValueType.NUMBER).getNumber().intValue();
			int segmentTimeout = event.getParameter("segment timeout", ValueType.NUMBER).getNumber().intValue();
			int segmentWindow = event.getParameter("segment window", ValueType.NUMBER).getNumber().intValue();
			int retries = event.getParameter("retries", ValueType.NUMBER).getNumber().intValue();
			int localDeviceId = event.getParameter("local device id", ValueType.NUMBER).getNumber().intValue();
			String localDeviceName = event.getParameter("local device name", ValueType.STRING).getString();
			String localDeviceVendor = event.getParameter("local device vendor", ValueType.STRING).getString();
			long interval = (long) (1000
					* event.getParameter("default polling interval", ValueType.NUMBER).getNumber().doubleValue());

			Node child = node.createChild(name, true).build();
			// IP transport
			child.setAttribute("broadcast ip", new Value(broadcastIp));
			child.setAttribute("port", new Value(port));
			child.setAttribute("local bind address", new Value(localBindAddress));
			child.setAttribute("register as foreign device in bbmd", new Value(isRegisteredAsFeignDevice));
			child.setAttribute(ATTRIBUTE_BBMD_IP_WITH_NETWORK_NUMBER, new Value(bbmdIpList));
			// MSTP transport
			child.setAttribute("comm port id", new Value(commPort));
			child.setAttribute("baud rate", new Value(baud));
			child.setAttribute("this station id", new Value(station));
			child.setAttribute("frame error retry count", new Value(frameErrorRetryCount));

			child.setAttribute("local network number", new Value(localNetworkNumber));
			child.setAttribute("strict device comparisons", new Value(strict));
			child.setAttribute("Timeout", new Value(timeout));
			child.setAttribute("segment timeout", new Value(segmentTimeout));
			child.setAttribute("segment window", new Value(segmentWindow));
			child.setAttribute("retries", new Value(retries));
			child.setAttribute("local device id", new Value(localDeviceId));
			child.setAttribute("local device name", new Value(localDeviceName));
			child.setAttribute("local device vendor", new Value(localDeviceVendor));
			child.setAttribute("default polling interval", new Value(interval));

			BacnetConn conn = createBacnetConnection(getMe(), child);
			conn.init();
		}
	}

	private BacnetLink getMe() {
		return this;
	}

}
