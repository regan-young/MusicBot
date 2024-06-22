package com.jagrosh.jmusicbot.audio;

import com.sedmelluq.discord.lavaplayer.container.playlists.ExtendedM3uParser;
import com.sedmelluq.discord.lavaplayer.source.stream.M3uStreamSegmentUrlProvider;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import java.io.IOException;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpUriRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FMProvider extends M3uStreamSegmentUrlProvider {
  private static final Logger log = LoggerFactory.getLogger(FMProvider.class);

  private volatile String segmentPlaylistUrl;

  public FMProvider(String segmentPlaylistUrl) {
    this.segmentPlaylistUrl = segmentPlaylistUrl;

  }

  @Override
  protected String getQualityFromM3uDirective(ExtendedM3uParser.Line directiveLine) {
    return "default";
  }

  @Override
  protected String fetchSegmentPlaylistUrl(HttpInterface httpInterface) throws IOException {
    if (segmentPlaylistUrl != null) {
      return segmentPlaylistUrl;
    }
    return segmentPlaylistUrl;
  }

  @Override
  protected HttpUriRequest createSegmentGetRequest(String url) {
    return new HttpGet(url);
  }
}