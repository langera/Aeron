/*
 * Copyright 2014 - 2015 Real Logic Ltd.
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
package uk.co.real_logic.aeron.driver;

import uk.co.real_logic.aeron.driver.media.ReceiveChannelEndpoint;
import uk.co.real_logic.agrona.concurrent.status.ReadablePosition;

import java.util.IdentityHashMap;
import java.util.Map;

import static uk.co.real_logic.aeron.driver.Configuration.CLIENT_LIVENESS_TIMEOUT_NS;

/**
 * Subscription registration from a client used for liveness tracking
 */
public class SubscriptionLink implements DriverManagedResource
{
    private final long registrationId;
    private final int streamId;
    private final ReceiveChannelEndpoint channelEndpoint;
    private final AeronClient aeronClient;
    private final Map<NetworkedImage, ReadablePosition> positionByImageMap = new IdentityHashMap<>();
    private final DirectLog directLog;
    private final ReadablePosition directLogSubscriberPosition;

    private boolean reachedEndOfLife = false;

    public SubscriptionLink(
        final long registrationId,
        final ReceiveChannelEndpoint channelEndpoint,
        final int streamId,
        final AeronClient aeronClient)
    {
        this.registrationId = registrationId;
        this.channelEndpoint = channelEndpoint;
        this.streamId = streamId;
        this.aeronClient = aeronClient;
        this.directLog = null;
        this.directLogSubscriberPosition = null;
    }

    public SubscriptionLink(
        final long registrationId,
        final int streamId,
        final DirectLog directLog,
        final ReadablePosition subscriberPosition,
        final AeronClient aeronClient)
    {
        this.registrationId = registrationId;
        this.channelEndpoint = null; // will prevent matches between NetworkedImages and DirectLogs
        this.streamId = streamId;
        this.aeronClient = aeronClient;
        this.directLog = directLog;
        directLog.incRef();
        this.directLogSubscriberPosition = subscriberPosition;
    }

    public long registrationId()
    {
        return registrationId;
    }

    public ReceiveChannelEndpoint channelEndpoint()
    {
        return channelEndpoint;
    }

    public int streamId()
    {
        return streamId;
    }

    public long timeOfLastKeepaliveFromClient()
    {
        return aeronClient.timeOfLastKeepalive();
    }

    public boolean matches(final ReceiveChannelEndpoint channelEndpoint, final int streamId)
    {
        return channelEndpoint == this.channelEndpoint && streamId == this.streamId;
    }

    public void addImage(final NetworkedImage image, final ReadablePosition position)
    {
        positionByImageMap.put(image, position);
    }

    public void removeImage(final NetworkedImage image)
    {
        positionByImageMap.remove(image);
    }

    public void close()
    {
        positionByImageMap.forEach(NetworkedImage::removeSubscriber);

        if (null != directLog)
        {
            directLog.removeSubscription(directLogSubscriberPosition);
            directLog.decRef();
            directLogSubscriberPosition.close();
        }
    }

    public void onTimeEvent(final long time, final DriverConductor conductor)
    {
        if (time > (timeOfLastKeepaliveFromClient() + CLIENT_LIVENESS_TIMEOUT_NS))
        {
            reachedEndOfLife = true;
            conductor.cleanupSubscriptionLink(SubscriptionLink.this);
        }
    }

    public boolean hasReachedEndOfLife()
    {
        return reachedEndOfLife;
    }

    public void timeOfLastStateChange(final long time)
    {
        // not set this way
    }

    public long timeOfLastStateChange()
    {
        return timeOfLastKeepaliveFromClient();
    }

    public void delete()
    {
        close();
    }
}
