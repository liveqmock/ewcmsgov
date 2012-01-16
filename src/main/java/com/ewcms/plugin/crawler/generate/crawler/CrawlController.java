/**
 * Copyright (c)2010-2011 Enterprise Website Content Management System(EWCMS), All rights reserved.
 * EWCMS PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 * http://www.ewcms.com
 */
package com.ewcms.plugin.crawler.generate.crawler;

import com.ewcms.plugin.crawler.generate.fetcher.PageFetcher;
import com.ewcms.plugin.crawler.generate.frontier.DocIDServer;
import com.ewcms.plugin.crawler.generate.frontier.Frontier;
import com.ewcms.plugin.crawler.generate.robotstxt.RobotstxtServer;
import com.ewcms.plugin.crawler.generate.url.URLCanonicalizer;
import com.ewcms.plugin.crawler.generate.url.WebURL;
import com.ewcms.plugin.crawler.generate.util.IO;
import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentConfig;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The controller that manages a crawling session. This class creates
 * the crawler threads and monitors their progress.
 *
 * @author Yasser Ganjisaffar <lastname at gmail dot com>
 * @author wu_zhijun
 */
public class CrawlController extends Configurable {
	
	private static final Logger logger = LoggerFactory.getLogger(CrawlController.class.getName());

	private Map<String,Object> passingParameters;
	private Environment env;
	/**
	 * The 'customData' object can be used for passing custom crawl-related
	 * configurations to different components of the crawler.
	 */
	protected Object customData;

    /**
     * Once the crawling session finishes the controller
     * collects the local data of the crawler threads and stores
     * them in this List.
     */
    protected List<Object> crawlersLocalData = new ArrayList<Object>();

    /**
     * Is the crawling of this session finished?
     */
    protected boolean finished;

    /**
     * Is the crawling session set to 'shutdown'.
     * Crawler threads monitor this flag and when it is set
     * they will no longer process new pages.
     */
    protected boolean shuttingDown;

    protected PageFetcher pageFetcher;
	protected RobotstxtServer robotstxtServer;
	protected Frontier frontier;
	protected DocIDServer docIdServer;

	protected final Object waitingLock = new Object();

	public CrawlController(CrawlConfig config, PageFetcher pageFetcher, RobotstxtServer robotstxtServer, Map<String,Object> passingParameters) throws Exception {
		super(config);
		
		setPassingParameters(passingParameters);
		
		config.validate();
		File folder = new File(config.getCrawlStorageFolder());
		if (!folder.exists()) {
			if (!folder.mkdirs()) {
                throw new Exception("Couldn't create this folder: " + folder.getAbsolutePath());
            }
		}

		boolean resumable = config.isResumableCrawling();

		EnvironmentConfig envConfig = new EnvironmentConfig();
		envConfig.setAllowCreate(true);
		envConfig.setTransactional(resumable);
		envConfig.setLocking(resumable);

		File envHome = new File(config.getCrawlStorageFolder() + "/frontier");
		if (!envHome.exists()) {
			if (!envHome.mkdir()) {
                throw new Exception("Couldn't create this folder: " + envHome.getAbsolutePath());
            }
		}
		if (!resumable) {
			IO.deleteFolderContents(envHome);
		}
		
		env = new Environment(envHome, envConfig);
		docIdServer = new DocIDServer(env, config);
		frontier = new Frontier(env, config, docIdServer);

		this.pageFetcher = pageFetcher;
		this.robotstxtServer = robotstxtServer;

		finished = false;
		shuttingDown = false;
	}

    /**
     * Start the crawling session and wait for it to finish.
     *
     * @param _c  the class that implements the logic for crawler threads
     * @param numberOfCrawlers the number of concurrent threads that will be
     *                         contributing in this crawling session.
     */
	public <T extends WebCrawler> void start(final Class<T> _c, final int numberOfCrawlers) {
		this.start(_c, numberOfCrawlers, true);
	}

    /**
     * Start the crawling session and return immediately.
     *
     * @param _c  the class that implements the logic for crawler threads
     * @param numberOfCrawlers the number of concurrent threads that will be
     *                         contributing in this crawling session.
     */
	public <T extends WebCrawler> void startNonBlocking(final Class<T> _c, final int numberOfCrawlers) {
		this.start(_c, numberOfCrawlers, false);
	}

	protected <T extends WebCrawler> void start(final Class<T> _c, final int numberOfCrawlers, boolean isBlocking) {
		try {
			finished = false;
			crawlersLocalData.clear();
			final List<Thread> threads = new ArrayList<Thread>();
			final List<T> crawlers = new ArrayList<T>();

			for (int i = 1; i <= numberOfCrawlers; i++) {
				T crawler = _c.newInstance();
				Thread thread = new Thread(crawler, "Crawler " + i);
				crawler.setThread(thread);
				crawler.init(i, this);
				thread.start();
				crawlers.add(crawler);
				threads.add(thread);
				logger.info("Crawler " + i + " started.");
			}

			final CrawlController controller = this;

			Thread monitorThread = new Thread(new Runnable() {

				@Override
				public void run() {
					try {
						synchronized (waitingLock) {

							while (true) {
								sleep(10);
								boolean someoneIsWorking = false;
								for (int i = 0; i < threads.size(); i++) {
									Thread thread = threads.get(i);
									if (!thread.isAlive()) {
										if (!shuttingDown) {
											logger.info("Thread " + i + " was dead, I'll recreate it.");
											T crawler = _c.newInstance();
											thread = new Thread(crawler, "Crawler " + (i + 1));
											threads.remove(i);
											threads.add(i, thread);
											crawler.setThread(thread);
											crawler.init(i + 1, controller);
											thread.start();
											crawlers.remove(i);
											crawlers.add(i, crawler);
										}
									} else if (crawlers.get(i).isNotWaitingForNewURLs()) {
										someoneIsWorking = true;
									}
								}
								if (!someoneIsWorking) {
									// Make sure again that none of the threads
									// are
									// alive.
									logger.info("It looks like no thread is working, waiting for 10 seconds to make sure...");
									sleep(10);

									someoneIsWorking = false;
									for (int i = 0; i < threads.size(); i++) {
										Thread thread = threads.get(i);
										if (thread.isAlive() && crawlers.get(i).isNotWaitingForNewURLs()) {
											someoneIsWorking = true;
										}
									}
									if (!someoneIsWorking) {
										if (!shuttingDown) {
											long queueLength = frontier.getQueueLength();
											if (queueLength > 0) {
												continue;
											}
											logger.info("No thread is working and no more URLs are in queue waiting for another 10 seconds to make sure...");
											sleep(10);
											queueLength = frontier.getQueueLength();
											if (queueLength > 0) {
												continue;
											}
										}

										logger.info("All of the crawlers are stopped. Finishing the process...");
										// At this step, frontier notifies the
										// threads that were
										// waiting for new URLs and they should
										// stop
										frontier.finish();
										for (T crawler : crawlers) {
											crawler.onBeforeExit();
											crawlersLocalData.add(crawler.getMyLocalData());
										}

										logger.info("Waiting for 10 seconds before final clean up...");
										sleep(10);

										frontier.close();
										docIdServer.close();
										pageFetcher.shutDown();

										finished = true;
										waitingLock.notifyAll();

										return;
									}
								}
							}
						}
					} catch (Exception e) {
						e.printStackTrace();
					}
				}
			});

			monitorThread.start();

			if (isBlocking) {
				waitUntilFinish();
			}

		} catch (Exception e) {
			e.printStackTrace();
		}
	}

    /**
     * Wait until this crawling session finishes.
     */
	public void waitUntilFinish() {
		try{
			while (!finished) {
				synchronized (waitingLock) {
					if (finished) {
						return;
					}
					try {
						waitingLock.wait();
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}
			}
		}finally{
			try{
				if (env != null){
					env.cleanLog();
					env.close();
					env = null;
				}
			}catch(DatabaseException  e){
				logger.warn(e.getLocalizedMessage());
			}
		}
	}

    /**
     * Once the crawling session finishes the controller
     * collects the local data of the crawler threads and stores
     * them in a List. This function returns the reference to this
     * list.
     */
	public List<Object> getCrawlersLocalData() {
		return crawlersLocalData;
	}

	protected void sleep(int seconds) {
		try {
			Thread.sleep(seconds * 1000);
		} catch (Exception ignored) {
		}
	}

    /**
     * Adds a new seed URL. A seed URL is a URL that
     * is fetched by the crawler to extract new URLs
     * in it and follow them for crawling.
     *
     * @param pageUrl the URL of the seed
     */
	public void addSeed(String pageUrl) {
		String canonicalUrl = URLCanonicalizer.getCanonicalURL(pageUrl);
		if (canonicalUrl == null) {
			logger.error("Invalid seed URL: " + pageUrl);
			return;
		}
		int docid = docIdServer.getDocId(canonicalUrl);
		if (docid > 0) {
			// This URL is already seen.
			return;
		}

		WebURL webUrl = new WebURL();
		webUrl.setURL(canonicalUrl);
		docid = docIdServer.getNewDocID(canonicalUrl);
		webUrl.setDocid(docid);
		webUrl.setDepth((short) 0);
		if (!robotstxtServer.allows(webUrl)) {
			logger.info("Robots.txt does not allow this seed: " + pageUrl);
		} else {
			frontier.schedule(webUrl);
		}
	}

	public PageFetcher getPageFetcher() {
		return pageFetcher;
	}

	public void setPageFetcher(PageFetcher pageFetcher) {
		this.pageFetcher = pageFetcher;
	}

	public RobotstxtServer getRobotstxtServer() {
		return robotstxtServer;
	}

	public void setRobotstxtServer(RobotstxtServer robotstxtServer) {
		this.robotstxtServer = robotstxtServer;
	}

	public Frontier getFrontier() {
		return frontier;
	}

	public void setFrontier(Frontier frontier) {
		this.frontier = frontier;
	}

	public DocIDServer getDocIdServer() {
		return docIdServer;
	}

	public void setDocIdServer(DocIDServer docIdServer) {
		this.docIdServer = docIdServer;
	}

	public Object getCustomData() {
		return customData;
	}

	public void setCustomData(Object customData) {
		this.customData = customData;
	}

	public boolean isFinished() {
		return this.finished;
	}

	public boolean isShuttingDown() {
		return shuttingDown;
	}

    /**
     * Set the current crawling session set to 'shutdown'.
     * Crawler threads monitor the shutdown flag and when it is set
     * to true, they will no longer process new pages.
     */
	public void Shutdown() {
		logger.info("Shutting down...");
		this.shuttingDown = true;
		frontier.finish();
	}

	public Map<String, Object> getPassingParameters() {
		return passingParameters;
	}

	public void setPassingParameters(Map<String, Object> passingParameters) {
		this.passingParameters = passingParameters;
	}
}
