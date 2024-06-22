package com.jagrosh.jmusicbot.audio;

import com.sedmelluq.discord.lavaplayer.container.MediaContainerDescriptor;
import com.sedmelluq.discord.lavaplayer.container.MediaContainerProbe;
import com.sedmelluq.discord.lavaplayer.container.MediaContainerRegistry;
import com.sedmelluq.discord.lavaplayer.container.playlists.M3uPlaylistContainerProbe;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.ProbingAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioTrack;
import com.sedmelluq.discord.lavaplayer.tools.ExceptionTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpConfigurable;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.info.AudioTrackInfoBuilder;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.HttpClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Audio source manager which detects NZ FM Tracks tracks by URL.
 */
public class FMSourceManager extends ProbingAudioSourceManager implements AudioSourceManager, HttpConfigurable {
	private final HttpInterfaceManager httpInterfaceManager;
	private static final Logger log = LoggerFactory.getLogger(FMSourceManager.class);
	private static List<MediaContainerProbe> probes = Arrays.asList(new M3uPlaylistContainerProbe());

	/**
	 * Create an instance.
	 */
	public FMSourceManager() {
		this(new MediaContainerRegistry(probes));
	}

	public FMSourceManager(MediaContainerRegistry containerRegistry) {
		super(containerRegistry);
		httpInterfaceManager = HttpClientTools.createDefaultThreadLocalManager();
	}

	@Override
	public String getSourceName() {
		return "FMRadioSource";
	}

	@Override
	public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) {
		if (!reference.identifier.toLowerCase().contains("georgefm")
				&& !reference.identifier.toLowerCase().contains("theedge")
				&& !reference.identifier.toLowerCase().contains("magic")
				&& !reference.identifier.toLowerCase().contains("coastfm")
				&& !reference.identifier.toLowerCase().contains("newstalkzb")
				&& !reference.identifier.toLowerCase().contains("thebreeze")
				&& !reference.identifier.toLowerCase().contains("therock")
				&& !reference.identifier.toLowerCase().contains("hauraki")
				&& !reference.identifier.toLowerCase().contains("morefm")) {
			return null;
		}

		AudioReference newReference = new AudioReference(reference.identifier, reference.identifier);
		return createTrack(AudioTrackInfoBuilder.create(newReference, null).build(), newReference.containerDescriptor);
	}

	@Override
	public boolean isTrackEncodable(AudioTrack track) {
		return true;
	}

	@Override
	public void encodeTrack(AudioTrack track, DataOutput output) throws IOException {
	}

	@Override
	public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input) throws IOException {
		return new FMStreamAudioTrack(trackInfo, this);
	}

	/**
	 * @return Get an HTTP interface for a playing track.
	 */
	public HttpInterface getHttpInterface() {
		return httpInterfaceManager.getInterface();
	}

	@Override
	public void configureRequests(Function<RequestConfig, RequestConfig> configurator) {
		httpInterfaceManager.configureRequests(configurator);
	}

	@Override
	public void configureBuilder(Consumer<HttpClientBuilder> configurator) {
		httpInterfaceManager.configureBuilder(configurator);
	}

	@Override
	public void shutdown() {
		ExceptionTools.closeWithWarnings(httpInterfaceManager);
	}

	@Override
	protected AudioTrack createTrack(AudioTrackInfo trackInfo, MediaContainerDescriptor containerTrackFactory) {
		return new FMStreamAudioTrack(trackInfo, this);
	}
}