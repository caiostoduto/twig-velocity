package com.github.caiostoduto.twig;

import com.google.inject.Inject;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.plugin.Plugin;
import org.slf4j.Logger;

@Plugin(id = "twig_plugin", name = "Twig Plugin", version = BuildConstants.VERSION, url = "https://github.com/caiostoduto/twig-plugin", authors = {
    "Caio Stoduto" })
public class TwigPlugin {
  @Inject
  private Logger logger;

  @Subscribe
  public void onProxyInitialization(ProxyInitializeEvent event) {
    this.logger.info("Hello, World!");
  }
}
