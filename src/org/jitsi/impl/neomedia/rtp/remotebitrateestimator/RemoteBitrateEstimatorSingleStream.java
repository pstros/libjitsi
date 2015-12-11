/*
 * Copyright @ 2015 Atlassian Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jitsi.impl.neomedia.rtp.remotebitrateestimator;

import java.util.*;

import net.sf.fmj.media.rtp.util.*;

import org.jitsi.service.neomedia.rtp.*;
import org.jitsi.util.Logger;

/**
 * webrtc/webrtc/modules/remote_bitrate_estimator/remote_bitrate_estimator_single_stream.cc
 *
 * @author Lyubomir Marinov
 */
public class RemoteBitrateEstimatorSingleStream
    implements CallStatsObserver,
               RecurringProcessible,
               RemoteBitrateEstimator
{
    private static final Logger logger
        = Logger.getLogger(RemoteBitrateEstimatorSingleStream.class);

    private static final int kProcessIntervalMs = 1000;

    private static final int kStreamTimeOutMs = 2000;

    private final Object critSect = new Object();

    private final RateStatistics incomingBitrate
        = new RateStatistics(500, 8000F);

    /**
     * Reduces the effects of allocations and garbage collection of the method
     * <tt>updateEstimate</tt>.
     */
    private final RateControlInput input
        = new RateControlInput(BandwidthUsage.kBwNormal, 0L, 0D);

    private long lastProcessTime = -1L;

    private final RemoteBitrateObserver observer;

    private final Map<Integer,OveruseDetector> overuseDetectors
        = new HashMap<Integer,OveruseDetector>();

    private final RemoteRateControl remoteRate;

    /**
     * The set of synchronization source identifiers (SSRCs) currently being
     * received. Represents an unmodifiable copy/snapshot of the current keys of
     * {@link #overuseDetectors} suitable for public access and introduced for
     * the purposes of reducing the number of allocations and the effects of
     * garbage collection.
     */
    private Collection<Integer> ssrcs;

    public RemoteBitrateEstimatorSingleStream(
            RemoteBitrateObserver observer,
            long minBitrateBps)
    {
        this.observer = observer;
        remoteRate = new RemoteRateControl(minBitrateBps);
    }

    private long getExtensionTransmissionTimeOffset(RTPPacket header)
    {
        // TODO Auto-generated method stub
        return 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getLatestEstimate()
    {
        long bitrateBps;

        synchronized (critSect)
        {
            if (remoteRate.isValidEstimate())
            {
                if (getSsrcs().isEmpty())
                    bitrateBps = 0L;
                else
                    bitrateBps = remoteRate.getLatestEstimate();
            }
            else
            {
                bitrateBps = -1L;
            }
        }
        return bitrateBps;
    }

    @Override
    public Collection<Integer> getSsrcs()
    {
        synchronized (critSect)
        {
            if (ssrcs == null)
            {
                ssrcs
                    = Collections.unmodifiableCollection(
                            new ArrayList<Integer>(overuseDetectors.keySet()));
            }
            return ssrcs;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getTimeUntilNextProcess()
    {
        return
            (lastProcessTime < 0L)
                ? 0L
                : lastProcessTime
                    + kProcessIntervalMs
                    - System.currentTimeMillis();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void incomingPacket(
            long arrivalTimeMs,
            int payloadSize,
            int ssrc,
            long rtpTimestamp)
    {
        Integer ssrc_ = Integer.valueOf(ssrc);
        long nowMs = System.currentTimeMillis();

        synchronized (critSect)
        {
        OveruseDetector overuseDetector = overuseDetectors.get(ssrc_);

        if (overuseDetector == null)
        {
            // This is a new SSRC. Adding to map.
            // TODO(holmer): If the channel changes SSRC the old SSRC will still
            // be around in this map until the channel is deleted. This is OK
            // since the callback will no longer be called for the old SSRC.
            // This will be automatically cleaned up when we have one
            // RemoteBitrateEstimator per REMB group.
            overuseDetector = new OveruseDetector();
            overuseDetectors.put(ssrc_, overuseDetector);
            ssrcs = null;
        }
        overuseDetector.setPacketTimeMs(nowMs);
        this.incomingBitrate.update(payloadSize, nowMs);

        BandwidthUsage priorState = overuseDetector.getState();

        overuseDetector.update(payloadSize, -1L, rtpTimestamp, arrivalTimeMs, ssrc);
        if (overuseDetector.getState() == BandwidthUsage.kBwOverusing)
        {
            long incomingBitrate = this.incomingBitrate.getRate(nowMs);

            if (priorState != BandwidthUsage.kBwOverusing
                    || remoteRate.isTimeToReduceFurther(nowMs, incomingBitrate))
            {
                // The first overuse should immediately trigger a new estimate.
                // We also have to update the estimate immediately if we are
                // overusing and the target bitrate is too high compared to what
                // we are receiving.
                updateEstimate(nowMs);
            }
        }
        } // synchronized (critSect)
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void incomingPacket(
            long arrivalTimeMs,
            int payloadSize,
            RTPPacket header)
    {
        int ssrc = header.ssrc;
        long rtpTimestamp
            = header.timestamp + getExtensionTransmissionTimeOffset(header);

        incomingPacket(arrivalTimeMs, payloadSize, ssrc, rtpTimestamp);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onRttUpdate(long rtt)
    {
        synchronized (critSect)
        {
            remoteRate.setRtt(rtt);
        }
    }

    /**
     * Triggers a new estimate calculation.
     *
     * @return
     */
    @Override
    public long process()
    {
        if (getTimeUntilNextProcess() <= 0L)
        {
            long nowMs = System.currentTimeMillis();

            updateEstimate(nowMs);
            lastProcessTime = nowMs;
        }
        return 0L;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removeStream(int ssrc)
    {
        synchronized (critSect)
        {
            // Ignoring the return value which is the removed OveruseDetector.
            overuseDetectors.remove(Integer.valueOf(ssrc));
            ssrcs = null;
        }
    }

    /**
  * Triggers a new estimate calculation.
  *
  * @param nowMs
  * @param ssrc_
  */
  private void updateEstimate(long nowMs) {
      synchronized (critSect)
        {
        BandwidthUsage bwState = BandwidthUsage.kBwNormal;
        double sumNoiseVar = 0D;

        for (Iterator<OveruseDetector> it
                    = overuseDetectors.values().iterator();
                it.hasNext();)
        {
            OveruseDetector overuseDetector = it.next();
            long packetTimeMs = overuseDetector.getPacketTimeMs();

            if (packetTimeMs >= 0L && nowMs - packetTimeMs > kStreamTimeOutMs)
            {
                // This over-use detector hasn't received packets for
                // kStreamTimeOutMs milliseconds and is considered stale.
                it.remove();
                ssrcs = null;
            }
            else
            {
                sumNoiseVar += overuseDetector.getNoiseVar();

                // Make sure that we trigger an over-use if any of the over-use
                // detectors is detecting over-use.
                BandwidthUsage overuseDetectorBwState
                    = overuseDetector.getState();

                if (overuseDetectorBwState.ordinal() > bwState.ordinal())
                    bwState = overuseDetectorBwState;

            }
        }
        // We can't update the estimate if we don't have any active streams.
        if (overuseDetectors.isEmpty())
        {
            remoteRate.reset();
            return;
        }

        double meanNoiseVar = sumNoiseVar / (double) overuseDetectors.size();
        RateControlInput input = this.input;

        input.bwState = bwState;
        input.incomingBitRate = incomingBitrate.getRate(nowMs);
        input.noiseVar = meanNoiseVar;

        RateControlRegion region = remoteRate.update(input, nowMs);
        long targetBitrate = remoteRate.updateBandwidthEstimate(nowMs);

        /*
         *for (Iterator<OveruseDetector> it
         *    = overuseDetectors.values().iterator(); it.hasNext();) {
         *  OveruseDetector overuseDetector = it.next();
         */

          for (Integer ssrc: getSsrcs()) {
            long statssrc = 0XFFFFFFFFL & ssrc;
            //String stats = statssrc + "_EST_OFFSET:" + overuseDetector.getOffset()
              //+ "," + statssrc + "_EST_RELATIVE_INTERARRIVAL:" +
                //(overuseDetector.getTimeDeltas()[0] - overuseDetector.getTimeDeltas()[1])
              //+ "," + statssrc + "_EST_SIZE_DELTA:" +
                //(overuseDetector.getCurrentFrame().size - overuseDetector.getPrevFrame().size)
              //+ "," + statssrc + "_EST_CAP:" + (1.0/overuseDetector.getSlope())
              //+ "," + statssrc + "_EST_NOISE_VAR:" + overuseDetector.getNoiseVar()
              //+ "," + statssrc + "_EST_FRAME_ARRIVED:" + overuseDetector.getTimeDeltas()[0]
              //+ "," + statssrc + "_EST_FRAME_SENT:" + overuseDetector.getTimeDeltas()[1]
              ////+ "," + statssrc + "_EST_FRAME_CUR_SIZE:" + overuseDetector.getCurrentFrame().size
              //+ "," + statssrc + "_EST_FRAME_PREV_SIZE:" + overuseDetector.getPrevFrame().size
              ////+ "," + statssrc + "_EST_FRAME_CUR_TS:" + overuseDetector.getCurrentFrame().timestamp
              ////+ "," + statssrc + "_EST_FRAME_PREV_TS:" + overuseDetector.getPrevFrame().timestamp
              //+ "," + statssrc + "_EST_BWSTATE:" + input.bwState.ordinal()
            String stats = statssrc + "_EST_INCOMING_BITRATE:" + input.incomingBitRate
              + "," + statssrc + "_EST_REGION:" + region.ordinal()
              + "," + statssrc + "_EST_TARGET:" + targetBitrate;
            logger.trace("STAT " + stats);
          }
            //}

        if (remoteRate.isValidEstimate())
        {
            RemoteBitrateObserver observer = this.observer;

            if (observer != null)
                observer.onReceiveBitrateChanged(getSsrcs(), targetBitrate);
        }
        for (OveruseDetector overuseDetector : overuseDetectors.values())
            overuseDetector.setRateControlRegion(region);
        } // synchronized (critSect)
    }
}
