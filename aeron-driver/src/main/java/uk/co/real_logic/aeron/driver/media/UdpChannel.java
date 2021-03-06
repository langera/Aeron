/*
 * Copyright 2014 - 2016 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.aeron.driver.media;

import uk.co.real_logic.aeron.ErrorCode;
import uk.co.real_logic.aeron.driver.UriUtil;
import uk.co.real_logic.aeron.driver.exceptions.InvalidChannelException;
import uk.co.real_logic.aeron.driver.uri.AeronUri;
import uk.co.real_logic.aeron.driver.uri.InterfaceSearchAddress;
import uk.co.real_logic.agrona.BitUtil;

import java.net.*;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import static java.lang.System.lineSeparator;
import static java.net.InetAddress.getByAddress;
import static uk.co.real_logic.aeron.driver.NetworkUtil.*;
import static uk.co.real_logic.aeron.driver.Strings.isEmpty;

/**
 * Encapsulation of UDP Channels
 * <p>
 * Format of URI:
 * <code>
 * udp://[interface[:port]@]ip:port
 * </code>
 */
public final class UdpChannel
{
    private static final String UDP_MEDIA_ID = "udp";

    private static final String REMOTE_KEY = "remote";
    private static final String LOCAL_KEY = "local";
    private static final String INTERFACE_KEY = "interface";
    private static final String GROUP_KEY = "group";
    private static final String ENDPOINT_KEY = "endpoint";

    private final InetSocketAddress remoteData;
    private final InetSocketAddress localData;
    private final InetSocketAddress remoteControl;
    private final InetSocketAddress localControl;

    private final String uriStr;
    private final String canonicalForm;
    private final NetworkInterface localInterface;
    private final ProtocolFamily protocolFamily;

    /**
     * Parse URI and create channel
     *
     * @param uriStr to parse
     * @return created channel
     */
    public static UdpChannel parse(final String uriStr)
    {
        try
        {
            final AeronUri uri = parseIntoAeronUri(uriStr);

            validateConfiguration(uri);

            final Context context = new Context().uriStr(uriStr);

            final InetSocketAddress endpointAddress = getEndpointAddress(uri);

            if (null == endpointAddress)
            {
                throw new IllegalArgumentException("Aeron URIs for UDP must specify an endpoint address");
            }

            if (endpointAddress.getAddress().isMulticastAddress())
            {
                final byte[] addressAsBytes = endpointAddress.getAddress().getAddress();

                validateDataAddress(addressAsBytes);

                addressAsBytes[addressAsBytes.length - 1]++;
                final InetSocketAddress controlAddress =
                    new InetSocketAddress(getByAddress(addressAsBytes), endpointAddress.getPort());

                final InterfaceSearchAddress searchAddress = getInterfaceSearchAddress(uri);

                final NetworkInterface localInterface = findInterface(searchAddress);
                final InetSocketAddress localAddress = resolveToAddressOfInterface(localInterface, searchAddress);

                final ProtocolFamily protocolFamily = getProtocolFamily(endpointAddress.getAddress());

                context.localControlAddress(localAddress)
                    .remoteControlAddress(controlAddress)
                    .localDataAddress(localAddress)
                    .remoteDataAddress(endpointAddress)
                    .localInterface(localInterface)
                    .protocolFamily(protocolFamily)
                    .canonicalForm(canonicalise(localAddress, endpointAddress));
            }
            else
            {
                final InterfaceSearchAddress searchAddress = getInterfaceSearchAddress(uri);
                final InetSocketAddress localAddress;
                if (searchAddress.getInetAddress().isAnyLocalAddress())
                {
                    localAddress = searchAddress.getAddress();
                }
                else
                {
                    final NetworkInterface localInterface = findInterface(searchAddress);
                    localAddress = resolveToAddressOfInterface(localInterface, searchAddress);
                }

                final ProtocolFamily protocolFamily =
                    !uri.containsKey(LOCAL_KEY)
                        ? getProtocolFamily(endpointAddress.getAddress())
                        : getProtocolFamily(localAddress.getAddress());

                context.remoteControlAddress(endpointAddress)
                    .remoteDataAddress(endpointAddress)
                    .localControlAddress(localAddress)
                    .localDataAddress(localAddress)
                    .protocolFamily(protocolFamily)
                    .canonicalForm(canonicalise(localAddress, endpointAddress));
            }

            return new UdpChannel(context);
        }
        catch (final Exception ex)
        {
            throw new InvalidChannelException(ErrorCode.INVALID_CHANNEL, ex);
        }
    }

    private static InterfaceSearchAddress getInterfaceSearchAddress(final AeronUri uri) throws UnknownHostException
    {
        final InterfaceSearchAddress interfaceSearchAddress;

        if (uri.containsKey(INTERFACE_KEY))
        {
            interfaceSearchAddress = uri.getInterfaceSearchAddress(INTERFACE_KEY, InterfaceSearchAddress.wildcard());
        }
        else if (uri.containsKey(LOCAL_KEY))
        {
            interfaceSearchAddress = uri.getInterfaceSearchAddress(LOCAL_KEY, InterfaceSearchAddress.wildcard());
        }
        else
        {
            interfaceSearchAddress = InterfaceSearchAddress.wildcard();
        }

        return interfaceSearchAddress;
    }

    private static InetSocketAddress getEndpointAddress(final AeronUri uri) throws UnknownHostException
    {
        final InetSocketAddress endpointAddress;

        if (uri.containsKey(ENDPOINT_KEY))
        {
            endpointAddress = uri.getSocketAddress(ENDPOINT_KEY);
        }
        else if (uri.containsKey(GROUP_KEY))
        {
            endpointAddress = uri.getSocketAddress(GROUP_KEY);
        }
        else if (uri.containsKey(REMOTE_KEY))
        {
            endpointAddress = uri.getSocketAddress(REMOTE_KEY);
        }
        else
        {
            endpointAddress = null;
        }

        return endpointAddress;
    }

    private static void validateDataAddress(final byte[] addressAsBytes)
    {
        if (BitUtil.isEven(addressAsBytes[addressAsBytes.length - 1]))
        {
            throw new IllegalArgumentException("Multicast data address must be odd");
        }
    }

    private static void validateConfiguration(final AeronUri uri)
    {
        validateMedia(uri);
    }

    private static void validateMedia(final AeronUri uri)
    {
        if (!UDP_MEDIA_ID.equals(uri.media()))
        {
            throw new IllegalArgumentException("Udp channel only supports udp media: " + uri);
        }
    }

    private static AeronUri parseIntoAeronUri(final String uriStr)
        throws URISyntaxException, UnknownHostException
    {
        if (uriStr.startsWith("udp:"))
        {
            return parseUdpUriToAeronUri(uriStr);
        }
        else if (uriStr.startsWith("aeron:"))
        {
            return AeronUri.parse(uriStr);
        }

        throw new IllegalArgumentException("malformed channel URI: " + uriStr);
    }

    private static AeronUri parseUdpUriToAeronUri(final String uriStr)
        throws URISyntaxException, UnknownHostException
    {
        final URI uri = new URI(uriStr);
        final String userInfo = uri.getUserInfo();
        final int uriPort = uri.getPort();
        final Map<String, String> params = UriUtil.parseQueryString(uri, new HashMap<>());

        if (uriPort < 0)
        {
            throw new IllegalArgumentException("Port must be specified");
        }

        final InetAddress hostAddress = InetAddress.getByName(uri.getHost());

        if (hostAddress.isMulticastAddress())
        {
            final String group = uri.getHost() + ":" + uriPort;
            final String inf = interfaceStringOf(userInfo, params.get("subnetPrefix"));

            return AeronUri.builder()
                .media(UDP_MEDIA_ID)
                .param(GROUP_KEY, group)
                .param(INTERFACE_KEY, inf)
                .newInstance();
        }
        else
        {
            final String remote = uri.getHost() + ":" + uriPort;

            return AeronUri.builder()
                .media(UDP_MEDIA_ID)
                .param(REMOTE_KEY, remote)
                .param(LOCAL_KEY, userInfo)
                .newInstance();
        }
    }

    private static String interfaceStringOf(final String userInfo, final String subnetPrefix)
    {
        if (isEmpty(userInfo))
        {
            return null;
        }

        if (isEmpty(subnetPrefix))
        {
            return userInfo;
        }

        return userInfo + "/" + subnetPrefix;
    }

    private static InetSocketAddress resolveToAddressOfInterface(
        final NetworkInterface localInterface, final InterfaceSearchAddress searchAddress)
    {
        final InetAddress interfaceAddress = findAddressOnInterface(
            localInterface, searchAddress.getInetAddress(), searchAddress.getSubnetPrefix());

        if (null == interfaceAddress)
        {
            throw new IllegalStateException();
        }

        return new InetSocketAddress(interfaceAddress, searchAddress.getPort());
    }

    private static NetworkInterface findInterface(final InterfaceSearchAddress searchAddress)
        throws SocketException, UnknownHostException
    {
        final Collection<NetworkInterface> filteredIfcs = filterBySubnet(
            searchAddress.getInetAddress(), searchAddress.getSubnetPrefix());

        // Results are ordered by prefix length, with loopback at the end.
        for (final NetworkInterface ifc : filteredIfcs)
        {
            if (ifc.supportsMulticast() || ifc.isLoopback())
            {
                return ifc;
            }
        }

        throw new IllegalArgumentException(errorNoMatchingInterfaces(filteredIfcs, searchAddress));
    }

    /**
     * Remote data address information
     *
     * @return remote data address information
     */
    public InetSocketAddress remoteData()
    {
        return remoteData;
    }

    /**
     * Local data address information
     *
     * @return local data address information
     */
    public InetSocketAddress localData()
    {
        return localData;
    }

    /**
     * Remote control address information
     *
     * @return remote control address information
     */
    public InetSocketAddress remoteControl()
    {
        return remoteControl;
    }

    /**
     * Local control address information
     *
     * @return local control address information
     */
    public InetSocketAddress localControl()
    {
        return localControl;
    }

    private UdpChannel(final Context context)
    {
        this.remoteData = context.remoteData;
        this.localData = context.localData;
        this.remoteControl = context.remoteControl;
        this.localControl = context.localControl;
        this.uriStr = context.uriStr;
        this.canonicalForm = context.canonicalForm;
        this.localInterface = context.localInterface;
        this.protocolFamily = context.protocolFamily;
    }

    /**
     * The canonical form for the channel
     * <p>
     * {@link UdpChannel#canonicalise(java.net.InetSocketAddress, java.net.InetSocketAddress)}
     *
     * @return canonical form for channel
     */
    public String canonicalForm()
    {
        return canonicalForm;
    }

    public boolean equals(final Object o)
    {
        if (this == o)
        {
            return true;
        }

        if (o == null || getClass() != o.getClass())
        {
            return false;
        }

        final UdpChannel that = (UdpChannel)o;

        return !(canonicalForm != null ? !canonicalForm.equals(that.canonicalForm) : that.canonicalForm != null);
    }

    public int hashCode()
    {
        return canonicalForm != null ? canonicalForm.hashCode() : 0;
    }

    /**
     * {@inheritDoc}
     */
    public String toString()
    {
        return canonicalForm;
    }

    /**
     * Return a string which is a canonical form of the channel suitable for use as a file or directory
     * name and also as a method of hashing, etc.
     * <p>
     * A canonical form:
     * - begins with the string "UDP-"
     * - has all hostnames converted to hexadecimal
     * - has all fields expanded out
     * - uses "-" as all field separators
     * <p>
     * The general format is:
     * UDP-interface-localPort-remoteAddress-remotePort
     *
     * @param localData  for the channel
     * @param remoteData for the channel
     * @return canonical representation as a string
     */
    public static String canonicalise(final InetSocketAddress localData, final InetSocketAddress remoteData)
    {
        return String.format(
            "UDP-%1$s-%2$d-%3$s-%4$d",
            BitUtil.toHex(localData.getAddress().getAddress()),
            localData.getPort(),
            BitUtil.toHex(remoteData.getAddress().getAddress()),
            remoteData.getPort());
    }

    /**
     * Does channel represent a multicast or not
     *
     * @return does channel represent a multicast or not
     */
    public boolean isMulticast()
    {
        return remoteData.getAddress().isMulticastAddress();
    }

    /**
     * Local interface to be used by the channel
     *
     * @return {@link NetworkInterface} for the local interface used by the channel
     * @throws SocketException if an error occurs
     */
    public NetworkInterface localInterface() throws SocketException
    {
        return localInterface;
    }

    /**
     * Original URI of the channel URI.
     *
     * @return the original uri
     */
    public String originalUriString()
    {
        return uriStr;
    }

    public ProtocolFamily protocolFamily()
    {
        return protocolFamily;
    }

    private static class Context
    {
        private InetSocketAddress remoteData;
        private InetSocketAddress localData;
        private InetSocketAddress remoteControl;
        private InetSocketAddress localControl;
        private String uriStr;
        private String canonicalForm;
        private NetworkInterface localInterface;
        private ProtocolFamily protocolFamily;

        public Context uriStr(final String uri)
        {
            uriStr = uri;
            return this;
        }

        public Context remoteDataAddress(final InetSocketAddress remoteData)
        {
            this.remoteData = remoteData;
            return this;
        }

        public Context localDataAddress(final InetSocketAddress localData)
        {
            this.localData = localData;
            return this;
        }

        public Context remoteControlAddress(final InetSocketAddress remoteControl)
        {
            this.remoteControl = remoteControl;
            return this;
        }

        public Context localControlAddress(final InetSocketAddress localControl)
        {
            this.localControl = localControl;
            return this;
        }

        public Context canonicalForm(final String canonicalForm)
        {
            this.canonicalForm = canonicalForm;
            return this;
        }

        public Context localInterface(final NetworkInterface ifc)
        {
            this.localInterface = ifc;
            return this;
        }

        public Context protocolFamily(ProtocolFamily protocolFamily)
        {
            this.protocolFamily = protocolFamily;
            return this;
        }
    }

    private static String errorNoMatchingInterfaces(
        final Collection<NetworkInterface> filteredIfcs,
        final InterfaceSearchAddress address)
        throws SocketException
    {
        final StringBuilder builder = new StringBuilder();
        builder.append("Unable to find multicast interface matching criteria: ")
            .append(address.getAddress())
            .append("/")
            .append(address.getSubnetPrefix());

        if (filteredIfcs.size() > 0)
        {
            builder.append(lineSeparator()).append("  Candidates:");

            for (final NetworkInterface ifc : filteredIfcs)
            {
                builder.append(lineSeparator())
                    .append("  - Name: ")
                    .append(ifc.getDisplayName())
                    .append(", addresses: ")
                    .append(ifc.getInterfaceAddresses())
                    .append(", multicast: ")
                    .append(ifc.supportsMulticast());
            }
        }

        return builder.toString();
    }

    public String description()
    {
        final StringBuilder builder = new StringBuilder("UdpChannel - ");
        if (null != localInterface)
        {
            builder
                .append("interface: ")
                .append(localInterface.getDisplayName())
                .append(", ");
        }

        builder
            .append("localData: ").append(localData)
            .append(", remoteData: ").append(remoteData);

        return builder.toString();
    }
}
