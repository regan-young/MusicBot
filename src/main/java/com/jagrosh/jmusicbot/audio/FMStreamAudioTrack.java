package com.jagrosh.jmusicbot.audio;

import com.sedmelluq.discord.lavaplayer.container.adts.AdtsAudioTrack;
import com.sedmelluq.discord.lavaplayer.container.mpegts.MpegTsElementaryInputStream;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.stream.M3uStreamAudioTrack;
import com.sedmelluq.discord.lavaplayer.source.stream.M3uStreamSegmentUrlProvider;
import com.sedmelluq.discord.lavaplayer.tools.io.ChainedInputStream;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;

import java.io.InputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static com.sedmelluq.discord.lavaplayer.container.mpegts.MpegTsElementaryInputStream.ADTS_ELEMENTARY_STREAM;
/**
 * Audio track that handles processing NZ FM radio stations tracks.
 */
public class FMStreamAudioTrack extends M3uStreamAudioTrack {
	private static final Logger log = LoggerFactory.getLogger(FMStreamAudioTrack.class);

	private final FMSourceManager sourceManager;
	private final FMProvider segmentUrlProvider;
	private boolean isTS;

	/**
	 * @param trackInfo Track info
	 * @param sourceManager Source manager which was used to find this track
	 */
	public FMStreamAudioTrack(AudioTrackInfo trackInfo, FMSourceManager sourceManager) {
		super(trackInfo);
		this.isTS = false;
		this.sourceManager = sourceManager;
		
		String source = trackInfo.identifier;
		switch(trackInfo.identifier.toLowerCase()) {
			case "morefm":
				source = "https://livestream.mediaworks.nz/radio_origin/more_128kbps/chunklist.m3u8";
				this.isTS = true;
				break;
			case "magic":
				source = "https://livestream.mediaworks.nz/radio_origin/magic_128kbps/chunklist.m3u8";
				this.isTS = true;
				break;
			case "newstalkzb":
				source = "https://nz-ais-nzme.streamguys1.com/nz_004/playlist.m3u8?listeningSessionID=641196fc469aadd1_599679_OcvaFNZW_MjAyLjM3LjE0NS43NQ!!_00000002jOQ&downloadSessionID=0&aw_0_1st.playerid=iHeartRadioWebPlayer&aw_0_1st.skey=6875023698&clientType=web&companionAds=false&deviceName=web-desktop&dist=iheart&host=webapp.NZ&listenerId=&playedFrom=157&pname=live_profile&profileId=6875023698&stationid=6188&terminalId=162&territory=NZ";
				break;
			case "thebreeze":
				source = "https://livestream.mediaworks.nz/radio_origin/breeze_128kbps/chunklist.m3u8";
				this.isTS = true;
				break;
			case "coastfm":
				source = "https://ais-nzme.streamguys1.com/nz_062/playlist.m3u8?listeningSessionID=641196fc469aadd1_599361_f1t9Kv4C_MjAyLjM3LjE0NS43NQ!!_00000002jFO&downloadSessionID=0&aw_0_1st.playerid=iHeartRadioWebPlayer&aw_0_1st.skey=6875015649&clientType=web&companionAds=false&deviceName=web-desktop&dist=iheart&host=webapp.NZ&listenerId=&playedFrom=157&pname=live_profile&profileId=6875015649&stationid=9559&terminalId=162&territory=NZ";
				break;
			case "thedge":
				source = "https://digitalstreams.mediaworks.nz/edge_net/playlist.m3u8?listeningSessionID=640b0ae13e2477d4_740221_EtfHP7Oc__00000004btc&downloadSessionID=0";
				break;
			case "georgefm":
				source = "https://digitalstreams.mediaworks.nz/george_net/playlist.m3u8?listeningSessionID=640b0ae13e2477d4_678933_b6Im7UbN__00000003ZcD&downloadSessionID=0";
				break;
			case "therock":
				source = "https://digitalstreams.mediaworks.nz/rock_net/playlist.m3u8?listeningSessionID=640b0ae13e2477d4_740114_BXWDZ4Qh__00000004bqR&downloadSessionID=0";
				break;
			case "hauraki":
				source = "https://ais-nzme.streamguys1.com/nz_009/playlist.m3u8?listeningSessionID=641196fc469aadd1_313183_8W2EJbnD_MjAyLjM3LjE0NS43NQ!!_00000001aCc&downloadSessionID=0&aw_0_1st.playerid=iHeartRadioWebPlayer&aw_0_1st.skey=6860961035&clientType=web&companionAds=false&deviceName=web-desktop&dist=iheart&host=webapp.NZ&listenerId=&playedFrom=157&pname=live_profile&profileId=6860961035&stationid=6191&terminalId=162&territory=NZ";
		}
		
		this.segmentUrlProvider = new FMProvider(source);
	}

	@Override
	protected M3uStreamSegmentUrlProvider getSegmentUrlProvider() {
		return segmentUrlProvider;
	}

	@Override
	protected HttpInterface getHttpInterface() {
		return sourceManager.getHttpInterface();
	}

	@Override
	public void process(LocalAudioTrackExecutor localExecutor) throws Exception {
		try (final HttpInterface httpInterface = getHttpInterface()) {
			try (ChainedInputStream chainedInputStream = new ChainedInputStream(
							() -> getSegmentUrlProvider().getNextSegmentStream(httpInterface)))
			{
				processJoinedStream(localExecutor, chainedInputStream);
			}
		}
	}

	@Override
	protected AudioTrack makeShallowClone() {
		return new FMStreamAudioTrack(trackInfo, sourceManager);
	}

	@Override
	public AudioSourceManager getSourceManager() {
		return sourceManager;
	}

	@Override
	protected void processJoinedStream(LocalAudioTrackExecutor localExecutor, InputStream stream) throws Exception {
		InputStream st = stream;
		st = this.isTS ? new MpegTsElementaryInputStream(stream, ADTS_ELEMENTARY_STREAM) : stream;
		processDelegate(new AdtsAudioTrack(trackInfo, st), localExecutor);
	}
}
