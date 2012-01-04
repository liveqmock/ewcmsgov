/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ewcms.plugin.crawler.generate.robotstxt;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import com.ewcms.plugin.crawler.generate.crawler.Page;
import com.ewcms.plugin.crawler.generate.fetcher.PageFetchStatus;
import com.ewcms.plugin.crawler.generate.fetcher.PageFetcher;
import com.ewcms.plugin.crawler.generate.url.WebURL;
import com.ewcms.plugin.crawler.generate.util.Util;

/**
 * @author Yasser Ganjisaffar <lastname at gmail dot com>
 */

public class RobotstxtServer {

	protected RobotstxtConfig config;

	protected final Map<String, HostDirectives> host2directivesCache = new HashMap<String, HostDirectives>();

	protected PageFetcher pageFetcher;

	public RobotstxtServer(RobotstxtConfig config, PageFetcher pageFetcher) {
		this.config = config;
		this.pageFetcher = pageFetcher;
	}

	public boolean allows(WebURL webURL) {
		if (!config.isEnabled()) {
			return true;
		}
		try {
			URL url = new URL(webURL.getURL());
			String host = url.getHost().toLowerCase();
			String path = url.getPath();

			HostDirectives directives = host2directivesCache.get(host);

            if (directives != null && directives.needsRefetch()) {
                synchronized (host2directivesCache) {
                    host2directivesCache.remove(host);
                    directives = null;
                }
            }

			if (directives == null) {
				directives = fetchDirectives(host);
			}
			return directives.allows(path);
		} catch (MalformedURLException e) {
			e.printStackTrace();
		}
		return true;
	}

	private HostDirectives fetchDirectives(String host) {
		WebURL robotsTxtUrl = new WebURL();
		robotsTxtUrl.setURL("http://" + host + "/robots.txt");
		HostDirectives directives = null;
		try {
			int statusCode = pageFetcher.fetchHeader(robotsTxtUrl);
			if (statusCode == PageFetchStatus.OK) {
				Page page = new Page(robotsTxtUrl);
				pageFetcher.fetchContent(page);
				if (Util.hasPlainTextContent(page.getContentType())) {
					try {
						String content;
						if (page.getContentCharset() == null) {
							content = new String(page.getContentData());
						} else {
							content = new String(page.getContentData(), page.getContentCharset());
						}
						directives = RobotstxtParser.parse(content, config.getUserAgentName());
					} catch (Exception e) {
						e.printStackTrace();
					}
				}
			}
		} finally {
			pageFetcher.discardContentIfNotConsumed();
		}
		if (directives == null) {
			// We still need to have this object to keep track of the time we
			// fetched it
			directives = new HostDirectives();
		}
		synchronized (host2directivesCache) {
			if (host2directivesCache.size() == config.getCacheSize()) {
				String minHost = null;
				long minAccessTime = Long.MAX_VALUE;
				for (Entry<String, HostDirectives> entry : host2directivesCache.entrySet()) {
					if (entry.getValue().getLastAccessTime() < minAccessTime) {
						minAccessTime = entry.getValue().getLastAccessTime();
						minHost = entry.getKey();
					}
				}
				host2directivesCache.remove(minHost);
			}
			host2directivesCache.put(host, directives);
		}
		return directives;
	}

}
