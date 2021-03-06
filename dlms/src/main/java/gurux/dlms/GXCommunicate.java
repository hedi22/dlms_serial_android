package gurux.dlms;
//
// --------------------------------------------------------------------------
//  Gurux Ltd
// 
//
//
// Filename:        $HeadURL$
//
// Version:         $Revision$,
//                  $Date$
//                  $Author$
//
// Copyright (c) Gurux Ltd
//
//---------------------------------------------------------------------------
//
//  DESCRIPTION
//
// This file is a part of Gurux Device Framework.
//
// Gurux Device Framework is Open Source software; you can redistribute it
// and/or modify it under the terms of the GNU General Public License 
// as published by the Free Software Foundation; version 2 of the License.
// Gurux Device Framework is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of 
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. 
// See the GNU General Public License for more details.
//
// More information of Gurux products: http://www.gurux.org
//
// This code is licensed under the GNU General Public License v2. 
// Full text may be retrieved at http://www.gnu.org/licenses/gpl-2.0.txt
//---------------------------------------------------------------------------
import android.util.Log;
import android.widget.Toast;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Array;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map.Entry;

import gurux.common.GXCommon;
import gurux.common.IGXMedia;
import gurux.common.ReceiveParameters;
import gurux.dlms.enums.Authentication;
import gurux.dlms.enums.Conformance;
import gurux.dlms.enums.DataType;
import gurux.dlms.enums.ErrorCode;
import gurux.dlms.enums.InterfaceType;
import gurux.dlms.enums.ObjectType;
import gurux.dlms.enums.RequestTypes;
import gurux.dlms.manufacturersettings.GXManufacturer;
import gurux.dlms.manufacturersettings.GXServerAddress;
import gurux.dlms.manufacturersettings.HDLCAddressType;
import gurux.dlms.objects.GXDLMSCaptureObject;
import gurux.dlms.objects.GXDLMSDemandRegister;
import gurux.dlms.objects.GXDLMSObject;
import gurux.dlms.objects.GXDLMSObjectCollection;
import gurux.dlms.objects.GXDLMSProfileGeneric;
import gurux.dlms.objects.GXDLMSRegister;
import gurux.dlms.objects.IGXDLMSBase;
import gurux.dlms.secure.GXDLMSSecureClient;
import gurux.io.Parity;
import gurux.io.StopBits;

public class GXCommunicate {
    IGXMedia Media;
    public boolean Trace = false;
    GXManufacturer manufacturer;
    public GXDLMSClient dlms;
    java.nio.ByteBuffer replyBuff;
    int WaitTime = 10000;

    static void trace(PrintWriter logFile, String text) {
        logFile.write(text);
        System.out.print(text);
    }

    static void traceLn(PrintWriter logFile, String text) {
        logFile.write(text + "\r\n");
        System.out.print(text + "\r\n");
    }

    public GXCommunicate(int waitTime, GXDLMSSecureClient dlms,
                         GXManufacturer manufacturer, Authentication auth,
                         String pw, IGXMedia media) throws Exception {
        Media = media;
        WaitTime = waitTime;
        this.dlms = dlms;
        this.manufacturer = manufacturer;
        dlms.setUseLogicalNameReferencing(
                manufacturer.getUseLogicalNameReferencing());
        int value = manufacturer.getAuthentication(auth).getClientAddress();
        dlms.setClientAddress(value);
        GXServerAddress serv = manufacturer.getServer(HDLCAddressType.DEFAULT);

            dlms.setInterfaceType(InterfaceType.HDLC);
            value = GXDLMSClient.getServerAddress(serv.getLogicalAddress(),
                    serv.getPhysicalAddress());
        dlms.setServerAddress(value);
        dlms.setAuthentication(auth);
        dlms.setPassword(pw.getBytes("ASCII"));
        System.out.println("Authentication: " + auth);
        System.out.println("ClientAddress: 0x"
                + Integer.toHexString(dlms.getClientAddress()));
        System.out.println("ServerAddress: 0x"
                + Integer.toHexString(dlms.getServerAddress()));

            replyBuff = java.nio.ByteBuffer.allocate(100);
    }

    public GXCommunicate(int waitTime, GXDLMSClient dlms, IGXMedia media) throws Exception {
        Media = media;
        WaitTime = waitTime;
        this.dlms = dlms;
        dlms.setUseLogicalNameReferencing(true);
        dlms.setClientAddress(16);
        dlms.setInterfaceType(InterfaceType.HDLC);
        dlms.setServerAddress(1);
        replyBuff = java.nio.ByteBuffer.allocate(100);
    }

    void close() throws Exception {
        if (Media != null) {
            System.out.println("DisconnectRequest");
            GXReplyData reply = new GXReplyData();
            readDLMSPacket(dlms.disconnectRequest(), reply);
            Media.close();
        }
    }

    String now() {
        return new SimpleDateFormat("HH:mm:ss.SSS")
                .format(java.util.Calendar.getInstance().getTime());
    }

    void writeTrace(String line) {
        if (Trace) {
            System.out.println(line);
        }
        PrintWriter logFile = null;
        try {
            logFile = new PrintWriter(
                    new BufferedWriter(new FileWriter("trace.txt", true)));
            logFile.println(line);
        } catch (IOException ex) {
            throw new RuntimeException(ex.getMessage());
        } finally {
            if (logFile != null) {
                logFile.close();
            }
        }
    }

    public void readDLMSPacket(byte[][] data) throws Exception {
        GXReplyData reply = new GXReplyData();
        for (byte[] it : data) {
            reply.clear();
            readDLMSPacket(it, reply);
        }
    }

    /**
     * Read DLMS Data from the device. If access is denied return null.
     */
    public void readDLMSPacket(byte[] data, GXReplyData reply)
            throws Exception {
        if (data == null || data.length == 0) {
            return;
        }
        reply.setError((short) 0);
        Object eop = (byte) 0x7E;
        Integer pos = 0;
        boolean succeeded = false;
        ReceiveParameters<byte[]> p =
                new ReceiveParameters<byte[]>(byte[].class);
        p.setEop(eop);
        p.setCount(5);
        p.setWaitTime(WaitTime);
        synchronized (Media.getSynchronous()) {
            while (!succeeded) {
                writeTrace("<- " + now() + "\t" + GXCommon.bytesToHex(data));
                Media.send(data, null);
                if (p.getEop() == null) {
                    p.setCount(1);
                }
                succeeded = Media.receive(p);
                if (!succeeded) {
                    // Try to read again...
                    if (pos++ == 3) {
                        throw new RuntimeException(
                                "Failed to receive reply from the device in given time.");
                    }
                    System.out.println("Data send failed. Try to resend "
                            + pos.toString() + "/3");
                }
            }
            // Loop until whole DLMS packet is received.
            try {
                while (!dlms.getData(p.getReply(), reply)) {
                    if (p.getEop() == null) {
                        p.setCount(1);
                    }
                    if (!Media.receive(p)) {
                        // If echo.
                        if (reply.isEcho()) {
                            Media.send(data, null);
                        }
                        // Try to read again...
                        if (++pos == 3) {
                            throw new Exception(
                                    "Failed to receive reply from the device in given time.");
                        }
                        System.out.println("Data send failed. Try to resend "
                                + pos.toString() + "/3");
                    }
                }
            } catch (Exception e) {
                writeTrace("-> " + now() + "\t"
                        + GXCommon.bytesToHex(p.getReply()));
                throw e;
            }
        }
        writeTrace("-> " + now() + "\t" + GXCommon.bytesToHex(p.getReply()));
        if (reply.getError() != 0) {
            if (reply.getError() == ErrorCode.REJECTED.getValue()) {
                Thread.sleep(1000);
                readDLMSPacket(data, reply);
            } else {
                throw new GXDLMSException(reply.getError());
            }
        }
    }

    void readDataBlock(byte[][] data, GXReplyData reply) throws Exception {
        for (byte[] it : data) {
            reply.clear();
            readDataBlock(it, reply);
        }
    }

    /**
     * Reads next data block.
     *
     * @param data
     * @return
     * @throws Exception
     */
    void readDataBlock(byte[] data, GXReplyData reply) throws Exception {
        RequestTypes rt;
        if (data.length != 0) {
            readDLMSPacket(data, reply);
            while (reply.isMoreData()) {
                rt = reply.getMoreData();
                data = dlms.receiverReady(rt);
                readDLMSPacket(data, reply);
            }
        }
    }

    /**
     * Initializes connection.
     *
     * @throws InterruptedException
     * @throws Exception
     */
    public void initializeConnection() throws Exception, InterruptedException {
        Media.open();
        if (Media.isOpen()) {
            Log.d("GXCommunicate", "Media opened ");
            GXReplyData reply = new GXReplyData();
            Log.d("GXCommunicate", "Sending snrm");
            byte[] data = dlms.snrmRequest();
            if (data.length != 0) {
                readDLMSPacket(data, reply);
                // Has server accepted client.
                dlms.parseUAResponse(reply.getData());

                // Allocate buffer to same size as transmit buffer of the meter.
                // Size of replyBuff is payload and frame (Bop, EOP, crc).
                int size = (int) ((((Number) dlms.getLimits().getMaxInfoTX())
                        .intValue() & 0xFFFFFFFFL) + 40);
                replyBuff = java.nio.ByteBuffer.allocate(size);
            }
            reply.clear();
            // Generate AARQ request.
            // Split requests to multiple packets if needed.
            // If password is used all data might not fit to one packet.
            Log.d("GXCommunicate", "Sending aarq");
            for (byte[] it : dlms.aarqRequest()) {
                readDLMSPacket(it, reply);
            }
            // Parse reply.
            dlms.parseAareResponse(reply.getData());
            reply.clear();

            // Get challenge Is HLS authentication is used.
            if (dlms.getIsAuthenticationRequired()) {
                for (byte[] it : dlms.getApplicationAssociationRequest()) {
                    readDLMSPacket(it, reply);
                }
                dlms.parseApplicationAssociationResponse(reply.getData());
            }
        }
    }

    /**
     * Reads selected DLMS object with selected attribute index.
     *
     * @param item
     * @param attributeIndex
     * @return
     * @throws Exception
     */
    public Object readObject(GXDLMSObject item, int attributeIndex)
            throws Exception {
        byte[] data = dlms.read(item.getName(), item.getObjectType(),
                attributeIndex)[0];
        GXReplyData reply = new GXReplyData();

        readDataBlock(data, reply);
        // Update data type on read.
        if (item.getDataType(attributeIndex) == DataType.NONE) {
            item.setDataType(attributeIndex, reply.getValueType());
        }
        return dlms.updateValue(item, attributeIndex, reply.getValue());
    }

    /*
     * /// Read list of attributes.
     */
    public void readList(List<Entry<GXDLMSObject, Integer>> list)
            throws Exception {
        byte[][] data = dlms.readList(list);
        GXReplyData reply = new GXReplyData();
        readDataBlock(data, reply);
        dlms.updateValues(list, Arrays.asList(reply.getValue()));
    }

    /**
     * Writes value to DLMS object with selected attribute index.
     *
     * @param item
     * @param attributeIndex
     * @throws Exception
     */
    public void writeObject(GXDLMSObject item, int attributeIndex)
            throws Exception {
        byte[][] data = dlms.write(item, attributeIndex);
        readDLMSPacket(data);
    }

    /*
     * Returns columns of profile Generic.
     */
    public List<Entry<GXDLMSObject, GXDLMSCaptureObject>>
    GetColumns(GXDLMSProfileGeneric pg) throws Exception {
        Object entries = readObject(pg, 7);
        System.out.println("Reading Profile Generic: " + pg.getLogicalName()
                + " " + pg.getDescription() + " entries:" + entries.toString());
        GXReplyData reply = new GXReplyData();
        byte[] data = dlms.read(pg.getName(), pg.getObjectType(), 3)[0];
        readDataBlock(data, reply);
        dlms.updateValue((GXDLMSObject) pg, 3, reply.getValue());
        return pg.getCaptureObjects();
    }

    /**
     * Read Profile Generic's data by entry start and count.
     *
     * @param pg
     * @param index
     * @param count
     * @return
     * @throws Exception
     */
    public Object[] readRowsByEntry(GXDLMSProfileGeneric pg, int index,
                                    int count) throws Exception {
        byte[][] data = dlms.readRowsByEntry(pg, index, count);
        GXReplyData reply = new GXReplyData();
        readDataBlock(data, reply);
        return (Object[]) dlms.updateValue(pg, 2, reply.getValue());
    }

    /**
     * Read Profile Generic's data by range (start and end time).
     *
     * @param pg
     * @param start
     * @param end
     * @return
     * @throws Exception
     */
    public Object[] readRowsByRange(final GXDLMSProfileGeneric pg,
                                    final Date start, final Date end) throws Exception {
        GXReplyData reply = new GXReplyData();
        byte[][] data = dlms.readRowsByRange(pg, start, end);
        readDataBlock(data, reply);
        return (Object[]) dlms.updateValue(pg, 2, reply.getValue());
    }

    /*
     * Read Scalers and units from the register objects.
     */
    void readScalerAndUnits(final GXDLMSObjectCollection objects,
                            final PrintWriter logFile) throws Exception {
        GXDLMSObjectCollection objs = objects.getObjects(new ObjectType[] {
                ObjectType.REGISTER, ObjectType.DEMAND_REGISTER,
                ObjectType.EXTENDED_REGISTER });
        try {
            if (dlms.getNegotiatedConformance()
                    .contains(Conformance.MULTIPLE_REFERENCES)) {
                List<Entry<GXDLMSObject, Integer>> list =
                        new ArrayList<Entry<GXDLMSObject, Integer>>();
                for (GXDLMSObject it : objs) {
                    if (it instanceof GXDLMSRegister) {
                        list.add(new GXSimpleEntry<GXDLMSObject, Integer>(it,
                                3));
                    }
                    if (it instanceof GXDLMSDemandRegister) {
                        list.add(new GXSimpleEntry<GXDLMSObject, Integer>(it,
                                4));
                    }
                }
                readList(list);
            }
        } catch (Exception e) {
            // Some meters are set multiple references, but don't support it.
            dlms.getNegotiatedConformance()
                    .remove(Conformance.MULTIPLE_REFERENCES);
        }
        if (!dlms.getNegotiatedConformance()
                .contains(Conformance.MULTIPLE_REFERENCES)) {
            for (GXDLMSObject it : objs) {
                try {
                    if (it instanceof GXDLMSRegister) {
                        readObject(it, 3);
                    } else if (it instanceof GXDLMSDemandRegister) {
                        readObject(it, 4);
                    }
                } catch (Exception e) {
                    traceLn(logFile,
                            "Err! Failed to read scaler and unit value: "
                                    + e.getMessage());
                    // Continue reading.
                }
            }
        }
    }

    /*
     * Read profile generic columns from the meter.
     */
    void readProfileGenericColumns(final GXDLMSObjectCollection objects,
                                   final PrintWriter logFile) {
        GXDLMSObjectCollection profileGenerics =
                objects.getObjects(ObjectType.PROFILE_GENERIC);
        for (GXDLMSObject it : profileGenerics) {
            traceLn(logFile, "Profile Generic " + it.getName() + " Columns:");
            GXDLMSProfileGeneric pg = (GXDLMSProfileGeneric) it;
            // Read columns.
            try {
                readObject(pg, 3);
                boolean first = true;
                StringBuilder sb = new StringBuilder();
                for (Entry<GXDLMSObject, GXDLMSCaptureObject> col : pg
                        .getCaptureObjects()) {
                    if (!first) {
                        sb.append(" | ");
                    }
                    sb.append(col.getKey().getName());
                    sb.append(" ");
                    String desc = col.getKey().getDescription();
                    if (desc != null) {
                        sb.append(desc);
                    }
                    first = false;
                }
                traceLn(logFile, sb.toString());
            } catch (Exception ex) {
                traceLn(logFile,
                        "Err! Failed to read columns:" + ex.getMessage());
                // Continue reading.
            }
        }
    }

    /**
     * Read all data from the meter except profile generic (Historical) data.
     */
    void readValues(final GXDLMSObjectCollection objects,
                    final PrintWriter logFile) {
        for (GXDLMSObject it : objects) {
            if (!(it instanceof IGXDLMSBase)) {
                // If interface is not implemented.
                System.out.println(
                        "Unknown Interface: " + it.getObjectType().toString());
                continue;
            }

            if (it instanceof GXDLMSProfileGeneric) {
                // Profile generic are read later
                // because it might take so long time
                // and this is only a example.
                continue;
            }
            traceLn(logFile,
                    "-------- Reading " + it.getClass().getSimpleName() + " "
                            + it.getName().toString() + " "
                            + it.getDescription());
            for (int pos : ((IGXDLMSBase) it).getAttributeIndexToRead()) {
                try {
                    Object val = readObject(it, pos);
                    if (val instanceof byte[]) {
                        val = GXCommon.bytesToHex((byte[]) val);
                    } else if (val instanceof Double) {
                        NumberFormat formatter =
                                NumberFormat.getNumberInstance();
                        val = formatter.format(val);
                    } else if (val != null && val.getClass().isArray()) {
                        String str = "";
                        for (int pos2 = 0; pos2 != Array
                                .getLength(val); ++pos2) {
                            if (!str.equals("")) {
                                str += ", ";
                            }
                            Object tmp = Array.get(val, pos2);
                            if (tmp instanceof byte[]) {
                                str += GXCommon.bytesToHex((byte[]) tmp);
                            } else {
                                str += String.valueOf(tmp);
                            }
                        }
                        val = str;
                    }
                    traceLn(logFile,
                            "Index: " + pos + " Value: " + String.valueOf(val));
                } catch (Exception ex) {
                    traceLn(logFile,
                            "Error! Index: " + pos + " " + ex.getMessage());
                    // Continue reading.
                }
            }
        }
    }

    /**
     * Read profile generic (Historical) data.
     */
    void readProfileGenerics(final GXDLMSObjectCollection objects,
                             final PrintWriter logFile) throws Exception {
        Object[] cells;
        GXDLMSObjectCollection profileGenerics =
                objects.getObjects(ObjectType.PROFILE_GENERIC);
        for (GXDLMSObject it : profileGenerics) {
            traceLn(logFile,
                    "-------- Reading " + it.getClass().getSimpleName() + " "
                            + it.getName().toString() + " "
                            + it.getDescription());

            long entriesInUse = ((Number) readObject(it, 7)).longValue();
            long entries = ((Number) readObject(it, 8)).longValue();
            traceLn(logFile, "Entries: " + String.valueOf(entriesInUse) + "/"
                    + String.valueOf(entries));
            GXDLMSProfileGeneric pg = (GXDLMSProfileGeneric) it;
            // If there are no columns.
            if (entriesInUse == 0 || pg.getCaptureObjects().size() == 0) {
                continue;
            }
            ///////////////////////////////////////////////////////////////////
            // Read first item.
            try {
                cells = readRowsByEntry(pg, 1, 1);
                for (Object rows : cells) {
                    for (Object cell : (Object[]) rows) {
                        if (cell instanceof byte[]) {
                            trace(logFile,
                                    GXCommon.bytesToHex((byte[]) cell) + " | ");
                        } else {
                            trace(logFile, cell + " | ");
                        }
                    }
                    traceLn(logFile, "");
                }
            } catch (Exception ex) {
                traceLn(logFile,
                        "Error! Failed to read first row: " + ex.getMessage());
                // Continue reading if device returns access denied error.
            }
            ///////////////////////////////////////////////////////////////////
            // Read last day.
            try {
                java.util.Calendar start = java.util.Calendar
                        .getInstance(java.util.TimeZone.getTimeZone("UTC"));
                start.set(java.util.Calendar.HOUR_OF_DAY, 0); // set hour to
                // midnight
                start.set(java.util.Calendar.MINUTE, 0); // set minute in
                // hour
                start.set(java.util.Calendar.SECOND, 0); // set second in
                // minute
                start.set(java.util.Calendar.MILLISECOND, 0);
                start.add(java.util.Calendar.DATE, -1);

                java.util.Calendar end = java.util.Calendar.getInstance();
                end.set(java.util.Calendar.MINUTE, 0); // set minute in hour
                end.set(java.util.Calendar.SECOND, 0); // set second in
                // minute
                end.set(java.util.Calendar.MILLISECOND, 0);
                cells = readRowsByRange((GXDLMSProfileGeneric) it,
                        start.getTime(), end.getTime());
                for (Object rows : cells) {
                    for (Object cell : (Object[]) rows) {
                        if (cell instanceof byte[]) {
                            System.out.print(
                                    GXCommon.bytesToHex((byte[]) cell) + " | ");
                        } else {
                            trace(logFile, cell + " | ");
                        }
                    }
                    traceLn(logFile, "");
                }
            } catch (Exception ex) {
                traceLn(logFile,
                        "Error! Failed to read last day: " + ex.getMessage());
                // Continue reading if device returns access denied error.
            }
        }
    }

    /*
     * Read all objects from the meter. This is only example. Usually there is
     * no need to read all data from the meter.
     */
    void readAllObjects(PrintWriter logFile) throws Exception {
        System.out.println("Reading association view");
        GXReplyData reply = new GXReplyData();
        // Get Association view from the meter.
        readDataBlock(dlms.getObjectsRequest(), reply);
        GXDLMSObjectCollection objects =
                dlms.parseObjects(reply.getData(), true);
        // Get description of the objects.
        GXDLMSConverter converter = new GXDLMSConverter();
        converter.updateOBISCodeInformation(objects);

        // Read Scalers and units from the register objects.
        readScalerAndUnits(objects, logFile);
        // Read Profile Generic columns.
        readProfileGenericColumns(objects, logFile);
        // Read all attributes from all objects.
        readValues(objects, logFile);
        // Read historical data.
        readProfileGenerics(objects, logFile);
    }
}