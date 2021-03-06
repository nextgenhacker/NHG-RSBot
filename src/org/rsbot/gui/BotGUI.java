package org.rsbot.gui;

import org.rsbot.Configuration;
import org.rsbot.Configuration.OperatingSystem;
import org.rsbot.bot.Bot;
import org.rsbot.jna.win32.Kernel32;
import org.rsbot.locale.Messages;
import org.rsbot.log.TextAreaLogHandler;
import org.rsbot.script.Script;
import org.rsbot.script.ScriptManifest;
import org.rsbot.script.internal.ScriptHandler;
import org.rsbot.script.internal.event.ScriptListener;
import org.rsbot.script.methods.Environment;
import org.rsbot.script.methods.Web;
import org.rsbot.script.provider.ScriptDownloader;
import org.rsbot.script.task.LoopTask;
import org.rsbot.script.util.WindowUtil;
import org.rsbot.script.util.io.WebQueue;
import org.rsbot.service.Preferences;
import org.rsbot.service.TwitterUpdates;
import org.rsbot.util.UpdateChecker;
import org.rsbot.util.Win32;
import org.rsbot.util.io.IOHelper;
import org.rsbot.util.io.ScreenshotUtil;

import javax.swing.*;
import java.awt.*;
import java.awt.TrayIcon.MessageType;
import java.awt.event.*;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.List;
import java.util.logging.Logger;

/**
 * @author Paris
 */
public class BotGUI extends JFrame implements ActionListener, ScriptListener {
	public static final int PANEL_WIDTH = 765, PANEL_HEIGHT = 503, LOG_HEIGHT = 120;
	public static final int MAX_BOTS = 6;
	private static final long serialVersionUID = -5411033752001988794L;
	private static final Logger log = Logger.getLogger(BotGUI.class.getName());
	private SettingsManager settings;
	private Preferences preferences;
	private BotPanel panel;
	private BotToolBar toolBar;
	private BotMenuBar menuBar;
	private JScrollPane textScroll;
	protected static final List<Bot> bots = new ArrayList<Bot>();
	private TrayIcon tray = null;
	private java.util.Timer shutdown = null;

	public BotGUI() {
		init();
		pack();
		setTitle(null);
		setLocationRelativeTo(getOwner());
		setMinimumSize(new Dimension((int) (getSize().width * .8), (int) (getSize().height * .8)));
		setResizable(true);
		settings = new SettingsManager(this);
		preferences = settings.getPreferences();
		preferences.load();
		preferences.commit();
		final BotGUI instance = this;
		SwingUtilities.invokeLater(new Runnable() {
			public void run() {
				LoadScreen.quit();
				JFrame.setDefaultLookAndFeelDecorated(true);
				JPopupMenu.setDefaultLightWeightPopupEnabled(false);
				ToolTipManager.sharedInstance().setLightWeightPopupEnabled(false);
				SwingUtilities.updateComponentTreeUI(instance);
				setVisible(true);
				addBot();
				updateScriptControls();
				setShutdownTimer(preferences.shutdown);
				if (!preferences.hideAds) {
					new SplashAd(BotGUI.this).display();
				}
				new Thread() {
					@Override
					public void run() {
						if (Configuration.Twitter.ENABLED) {
							TwitterUpdates.loadTweets(Configuration.Twitter.MESSAGES);
						}
						System.gc();
					}
				}.start();
			}
		});
		new java.util.Timer(true).schedule(new TimerTask() {
			@Override
			public void run() {
				Win32.emptyWorkingSet();
			}
		}, 1000 * 25, 1000 * 60 * 3);
		new java.util.Timer(true).schedule(new TimerTask() {
			@Override
			public void run() {
				if (Web.isLoaded() && Web.isInActive()) {
					Web.free();
				}
				if (!WebQueue.isEmpty()) {
					WebQueue.Start();
					return;
				}
				WebQueue.Destroy();
			}
		}, 0, 1000 * 30);
	}

	@Override
	public void setTitle(final String title) {
		String t = Configuration.NAME + " v" + Configuration.getVersionFormatted();
		final int v = Configuration.getVersion(), l = UpdateChecker.getLatestVersion();
		if (v > l) {
			t += " beta";
		}
		if (title != null) {
			t = title + " - " + t;
		}
		super.setTitle(t);
	}

	public void actionPerformed(final ActionEvent evt) {
		final String action = evt.getActionCommand();
		final String menu, option;
		final int z = action.indexOf('.');
		if (z == -1) {
			menu = action;
			option = "";
		} else {
			menu = action.substring(0, z);
			option = action.substring(z + 1);
		}
		if (menu.equals(Messages.CLOSEBOT)) {
			if (confirmRemoveBot()) {
				final int idx = Integer.parseInt(option);
				removeBot(bots.get(idx));
			}
		} else if (menu.equals(Messages.FILE)) {
			if (option.equals(Messages.NEWBOT)) {
				addBot();
			} else if (option.equals(Messages.CLOSEBOT)) {
				if (confirmRemoveBot()) {
					removeBot(getCurrentBot());
				}
			} else if (option.equals(Messages.ADDSCRIPT)) {
				final String pretext = "";
				final String key = (String) JOptionPane.showInputDialog(this, "Enter the script URL e.g. pastebin link or direct compiled file:",
						option, JOptionPane.QUESTION_MESSAGE, null, null, pretext);
				if (!(key == null || key.trim().isEmpty())) {
					ScriptDownloader.save(key);
				}
			} else if (option.equals(Messages.RUNSCRIPT)) {
				final Bot current = getCurrentBot();
				if (current != null) {
					showScriptSelector(current);
				}
			} else if (option.equals(Messages.STOPSCRIPT)) {
				final Bot current = getCurrentBot();
				if (current != null) {
					showStopScript(current);
				}
			} else if (option.equals(Messages.PAUSESCRIPT)) {
				final Bot current = getCurrentBot();
				if (current != null) {
					pauseScript(current);
				}
			} else if (option.equals(Messages.SAVESCREENSHOT)) {
				final Bot current = getCurrentBot();
				if (current != null && current.getMethodContext() != null) {
					ScreenshotUtil.saveScreenshot(current, current.getMethodContext().game.isLoggedIn());
				}
			} else if (option.equals(Messages.HIDEBOT)) {
				setTray();
			} else if (option.equals(Messages.EXIT)) {
				cleanExit(false);
			}
		} else if (menu.equals(Messages.EDIT)) {
			if (option.equals(Messages.ACCOUNTS)) {
				AccountManager.getInstance().showGUI();
			} else {
				final Bot current = getCurrentBot();
				if (current != null) {
					if (option.equals(Messages.FORCEINPUT)) {
						current.overrideInput = ((JCheckBoxMenuItem) evt.getSource()).isSelected();
						updateScriptControls();
					} else if (option.equals(Messages.LESSCPU)) {
						current.disableRendering = ((JCheckBoxMenuItem) evt.getSource()).isSelected();
					} else if (option.equals(Messages.DISABLECANVAS)) {
						current.disableGraphics = ((JCheckBoxMenuItem) evt.getSource()).isSelected();
					} else if (option.equals(Messages.EXTDVIEWS)) {
						menuBar.setExtendedView(((JCheckBoxMenuItem) evt.getSource()).isSelected());
					} else if (option.equals(Messages.DISABLEANTIRANDOMS)) {
						current.disableRandoms = ((JCheckBoxMenuItem) evt.getSource()).isSelected();
					} else if (option.equals(Messages.DISABLEAUTOLOGIN)) {
						current.disableAutoLogin = ((JCheckBoxMenuItem) evt.getSource()).isSelected();
					}
				}
			}
		} else if (menu.equals(Messages.VIEW)) {
			final Bot current = getCurrentBot();
			final boolean selected = ((JCheckBoxMenuItem) evt.getSource()).isSelected();
			if (option.equals(Messages.HIDETOOLBAR)) {
				toggleViewState(toolBar, selected);
			} else if (option.equals(Messages.HIDELOGPANE)) {
				toggleViewState(textScroll, selected);
			} else if (current != null) {
				if (option.equals(Messages.ALLDEBUGGING)) {
					for (final String key : BotMenuBar.DEBUG_MAP.keySet()) {
						final Class<?> el = BotMenuBar.DEBUG_MAP.get(key);
						if (menuBar.getCheckBox(key).isVisible()) {
							final boolean wasSelected = menuBar.getCheckBox(key).isSelected();
							menuBar.getCheckBox(key).setSelected(selected);
							if (selected) {
								if (!wasSelected) {
									current.addListener(el);
								}
							} else {
								if (wasSelected) {
									current.removeListener(el);
								}
							}
						}
					}
				} else {
					final Class<?> el = BotMenuBar.DEBUG_MAP.get(option);
					menuBar.getCheckBox(option).setSelected(selected);
					if (selected) {
						current.addListener(el);
					} else {
						menuBar.getCheckBox(Messages.ALLDEBUGGING).setSelected(false);
						current.removeListener(el);
					}
				}
			}
		} else if (menu.equals(Messages.TOOLS)) {
			if (option.equals(Messages.CLEARCACHE)) {
				final int result = JOptionPane.showConfirmDialog(this,
						"Delete all preferences and settings?\nNote: only use if the application is having errors.", option,
						JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
				if (result == JOptionPane.YES_OPTION) {
					IOHelper.recursiveDelete(new File(Configuration.Paths.getCacheDirectory()), false);
					IOHelper.recursiveDelete(new File(Configuration.Paths.getSettingsDirectory()), false);
					log.info("Cache cleared and preferences reset to defaults");
				}
			} else if (option.equals(Messages.OPTIONS)) {
				settings.display();
			}
		} else if (menu.equals(Messages.HELP)) {
			if (option.equals(Messages.SITE)) {
				openURL(Configuration.Paths.URLs.SITE);
			} else if (option.equals(Messages.PROJECT)) {
				openURL(Configuration.Paths.URLs.PROJECT);
			} else if (option.equals(Messages.LICENSE)) {
				LicenseDialog.showDialog(this);
			} else if (option.equals(Messages.ABOUT)) {
				JOptionPane.showMessageDialog(this, new String[]{"An open source bot developed by the community.", "Visit " + Configuration.Paths.URLs.SITE + "/ for more information."}, option, JOptionPane.INFORMATION_MESSAGE);
			}
		} else if (menu.equals("Tab")) {
			final Bot curr = getCurrentBot();
			menuBar.setBot(curr);
			panel.setBot(curr);
			panel.repaint();
			toolBar.setHome(curr == null);
			setTitle(curr == null ? null : curr.getAccountName());
			updateScriptControls();
		}
	}

	public void updateScriptControls() {
		boolean idle = true, paused = false;
		final Bot bot = getCurrentBot();

		if (bot != null) {
			final Map<Integer, LoopTask> scriptMap = bot.getScriptHandler().getRunningScripts();
			if ((bot.getMethodContext() == null || !bot.getMethodContext().web.areScriptsLoaded() || scriptMap.size() > Web.WEB_SCRIPT_COUNT) &&
					scriptMap.size() > 0) {
				idle = false;
				paused = scriptMap.values().iterator().next().isPaused();
			} else {
				idle = true;
			}
		}

		menuBar.getMenuItem(Messages.RUNSCRIPT).setVisible(idle);
		menuBar.getMenuItem(Messages.STOPSCRIPT).setVisible(!idle);
		menuBar.getMenuItem(Messages.PAUSESCRIPT).setEnabled(!idle);
		menuBar.setPauseScript(paused);
		toolBar.setInputButtonVisible(!idle);
		menuBar.setEnabled(Messages.FORCEINPUT, !idle);

		if (idle) {
			toolBar.setOverrideInput(false);
			menuBar.setOverrideInput(false);
			toolBar.setInputState(Environment.INPUT_KEYBOARD | Environment.INPUT_MOUSE);
			toolBar.setScriptButton(BotToolBar.RUN_SCRIPT);
		} else {
			toolBar.setOverrideInput(bot.overrideInput);
			toolBar.setOverrideInput(bot.overrideInput);
			toolBar.setInputState(bot.inputFlags);
			toolBar.setScriptButton(paused ? BotToolBar.RESUME_SCRIPT : BotToolBar.PAUSE_SCRIPT);
		}

		toolBar.updateInputButton();
		repaint();
	}

	public BotPanel getPanel() {
		return panel;
	}

	public Bot getBot(final Object o) {
		final ClassLoader cl = o.getClass().getClassLoader();
		for (final Bot bot : bots) {
			if (cl == bot.getLoader().getClient().getClass().getClassLoader()) {
				panel.offset();
				return bot;
			}
		}
		return null;
	}

	public void addBot() {
		if (bots.size() > MAX_BOTS) {
			return;
		}
		final Bot bot = new Bot();
		bots.add(bot);
		toolBar.addTab();
		toolBar.setAddTabVisible(bots.size() < MAX_BOTS);
		bot.getScriptHandler().addScriptListener(this);
		new Thread(new Runnable() {
			public void run() {
				bot.start();
			}
		}).start();
	}

	public void removeBot(final Bot bot) {
		final int idx = bots.indexOf(bot);
		bot.getScriptHandler().stopAllScripts();
		bot.getScriptHandler().removeScriptListener(this);
		if (idx >= 0) {
			toolBar.removeTab(idx);
		}
		bots.remove(idx);
		toolBar.setAddTabVisible(bots.size() < MAX_BOTS);
		new Thread(new Runnable() {
			public void run() {
				bot.stop();
				System.gc();
			}
		}).start();
	}

	void pauseScript(final Bot bot) {
		final ScriptHandler sh = bot.getScriptHandler();
		final Map<Integer, LoopTask> running = sh.getRunningScripts();
		if (running.size() > 0) {
			final int id = running.keySet().iterator().next();
			sh.pauseScript(id);
		}
	}

	private Bot getCurrentBot() {
		final int idx = toolBar.getCurrentTab();
		if (idx >= 0) {
			return bots.get(idx);
		}
		return null;
	}

	private void showScriptSelector(final Bot bot) {
		if (AccountManager.getAccountNames() == null || AccountManager.getAccountNames().length == 0) {
			JOptionPane.showMessageDialog(this, "No accounts found! Please create one before using the bot.");
			AccountManager.getInstance().showGUI();
		} else if (bot.getMethodContext() == null) {
			log.warning("The client is still loading");
		} else {
			new ScriptSelector(this, bot).showGUI();
		}
	}

	private void showStopScript(final Bot bot) {
		final ScriptHandler sh = bot.getScriptHandler();
		final Map<Integer, LoopTask> running = sh.getRunningScripts();
		if (running.size() > 0) {
			final int id = running.keySet().iterator().next();
			final Script s = (Script) running.get(id);
			final ScriptManifest prop = s.getClass().getAnnotation(ScriptManifest.class);
			final int result = JOptionPane.showConfirmDialog(this, "Would you like to stop the script " + prop.name() + "?", "Script", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
			if (result == JOptionPane.OK_OPTION) {
				sh.stopScript(id);
				updateScriptControls();
			}
		}
	}

	private void toggleViewState(final Component component, final boolean visible) {
		final Dimension size = getSize();
		size.height += component.getSize().height * (visible ? -1 : 1);
		component.setVisible(!visible);
		setMinimumSize(size);
		if ((getExtendedState() & Frame.MAXIMIZED_BOTH) != Frame.MAXIMIZED_BOTH) {
			pack();
		}
	}

	private void lessCpu(boolean on) {
		final Bot bot = getCurrentBot();
		if (bot != null) {
			disableRendering(on || menuBar.isTicked(Messages.LESSCPU));
			disableGraphics(on || menuBar.isTicked(Messages.DISABLECANVAS));
		}
		Win32.setProcessPriority(on ? Kernel32.PROCESS_MODE_BACKGROUND_BEGIN : Kernel32.PROCESS_MODE_BACKGROUND_END);
	}

	public void disableRendering(final boolean mode) {
		for (final Bot bot : bots) {
			bot.disableRendering = mode;
		}
	}

	public void disableGraphics(final boolean mode) {
		for (final Bot bot : bots) {
			bot.disableGraphics = mode;
		}
	}

	private void init() {
		setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
		addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(final WindowEvent e) {
				if (cleanExit(false)) {
					dispose();
				}
			}
		});
		addWindowStateListener(new WindowStateListener() {
			public void windowStateChanged(final WindowEvent arg0) {
				switch (arg0.getID()) {
					case WindowEvent.WINDOW_ICONIFIED:
						lessCpu(true);
						break;
					case WindowEvent.WINDOW_DEICONIFIED:
						lessCpu(false);
						break;
				}
			}
		});
		setIconImage(Configuration.getImage(Configuration.Paths.Resources.ICON));
		JPopupMenu.setDefaultLightWeightPopupEnabled(false);
		WindowUtil.setFrame(this);
		panel = new BotPanel();
		menuBar = new BotMenuBar(this);
		toolBar = new BotToolBar(this, menuBar);
		panel.setFocusTraversalKeys(0, new HashSet<AWTKeyStroke>());
		KeyboardFocusManager manager = KeyboardFocusManager.getCurrentKeyboardFocusManager();
		new KeyboardShortcuts(manager, this);
		menuBar.setBot(null);
		setJMenuBar(menuBar);
		textScroll = new JScrollPane(TextAreaLogHandler.TEXT_AREA, ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		textScroll.setBorder(null);
		textScroll.setPreferredSize(new Dimension(PANEL_WIDTH, LOG_HEIGHT));
		textScroll.setVisible(true);
		JScrollPane scrollableBotPanel = new JScrollPane(panel);
		add(toolBar, BorderLayout.NORTH);
		add(scrollableBotPanel, BorderLayout.CENTER);
		add(textScroll, BorderLayout.SOUTH);
	}

	public void scriptStarted(final ScriptHandler handler) {
		java.awt.EventQueue.invokeLater(new Runnable() {
			public void run() {
				final Bot bot = handler.getBot();
				bot.inputFlags = Environment.INPUT_KEYBOARD;
				bot.overrideInput = false;
				final String acct = bot.getAccountName();
				toolBar.setTabLabel(bots.indexOf(bot), acct == null ? Messages.TABDEFAULTTEXT : acct);
				if (bot == getCurrentBot()) {
					updateScriptControls();
					setTitle(acct);
				}
			}
		});
	}

	public void scriptStopped(final ScriptHandler handler) {
		java.awt.EventQueue.invokeLater(new Runnable() {
			public void run() {
				final Bot bot = handler.getBot();
				bot.inputFlags = Environment.INPUT_KEYBOARD | Environment.INPUT_MOUSE;
				bot.overrideInput = false;
				toolBar.setTabLabel(bots.indexOf(bot), Messages.TABDEFAULTTEXT);
				if (bot == getCurrentBot()) {
					updateScriptControls();
					setTitle(null);
				}
			}
		});
	}

	public void scriptResumed(final ScriptHandler handler) {
		if (handler.getBot() == getCurrentBot()) {
			updateScriptControls();
		}
	}

	public void scriptPaused(final ScriptHandler handler) {
		if (handler.getBot() == getCurrentBot()) {
			updateScriptControls();
		}
	}

	public void inputChanged(final Bot bot, final int mask) {
		bot.inputFlags = mask;
		updateScriptControls();
	}

	public static void openURL(final String url) {
		final Configuration.OperatingSystem os = Configuration.getCurrentOperatingSystem();
		try {
			if (os == Configuration.OperatingSystem.MAC) {
				final Class<?> fileMgr = Class.forName("com.apple.eio.FileManager");
				final Method openURL = fileMgr.getDeclaredMethod("openURL", new Class[]{String.class});
				openURL.invoke(null, url);
			} else if (os == Configuration.OperatingSystem.WINDOWS) {
				Runtime.getRuntime().exec("rundll32 url.dll,FileProtocolHandler " + url);
			} else {
				final String[] browsers = {"firefox", "opera", "konqueror", "epiphany", "mozilla", "netscape", "google-chrome", "chromium-browser"};
				String browser = null;
				for (int count = 0; count < browsers.length && browser == null; count++) {
					if (Runtime.getRuntime().exec(new String[]{"which", browsers[count]}).waitFor() == 0) {
						browser = browsers[count];
					}
				}
				if (browser == null) {
					throw new Exception("Could not find web browser");
				} else {
					Runtime.getRuntime().exec(new String[]{browser, url});
				}
			}
		} catch (final Exception e) {
			log.warning("Unable to open " + url);
		}
	}

	private boolean confirmRemoveBot() {
		if (!preferences.confirmations) {
			final int result = JOptionPane.showConfirmDialog(this, "Are you sure you want to close this bot?", Messages.CLOSEBOT, JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
			return result == JOptionPane.OK_OPTION;
		} else {
			return true;
		}
	}

	public void setShutdownTimer(final boolean enabled) {
		if (!enabled) {
			if (shutdown != null) {
				shutdown.cancel();
				shutdown.purge();
			}
			shutdown = null;
		} else {
			final long interval = preferences.shutdownTime * 60 * 1000;
			shutdown = new java.util.Timer(true);
			shutdown.schedule(new TimerTask() {
				@Override
				public void run() {
					for (final Bot bot : bots) {
						if (bot.getScriptHandler().getRunningScripts().size() != 0) {
							return;
						}
					}
					final int delay = 3;
					log.info("Shutdown pending in " + delay + " minutes...");
					final Point[] mouse = new Point[]{MouseInfo.getPointerInfo().getLocation(), null};
					try {
						Thread.sleep(delay * 60 * 1000);
					} catch (InterruptedException ignored) {
					}
					mouse[1] = MouseInfo.getPointerInfo().getLocation();
					if (mouse[0].x != mouse[1].x || mouse[0].y != mouse[1].y) {
						log.info("Mouse activity detected, delaying shutdown");
					} else if (!preferences.shutdown) {
						log.info("Shutdown cancelled");
					} else if (Configuration.getCurrentOperatingSystem() == OperatingSystem.WINDOWS) {
						try {
							Runtime.getRuntime().exec("shutdown.exe", new String[]{"-s"});
							cleanExit(true);
						} catch (IOException ignored) {
							log.severe("Could not shutdown system");
						}
					}
				}
			}, interval, interval);
		}
	}

	public boolean cleanExit(final boolean silent) {
		if (silent) {
			preferences.confirmations = true;
		}
		if (!preferences.confirmations) {
			preferences.confirmations = true;
			for (final Bot bot : bots) {
				if (bot.getAccountName() != null) {
					preferences.confirmations = true;
					break;
				}
			}
		}
		boolean doExit = true;
		if (!preferences.confirmations) {
			final String message = "Are you sure you want to exit?";
			final int result = JOptionPane.showConfirmDialog(this, message, Messages.EXIT, JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
			if (result != JOptionPane.OK_OPTION) {
				doExit = false;
			}
		}
		if (doExit) {
			Web.free();
			setVisible(false);
			try {
				WebQueue.Destroy();
			} catch (NoClassDefFoundError ignored) {
			}
			preferences.save();
			System.exit(0);
		}
		return doExit;
	}

	public void setTray() {
		boolean showTip = false;
		if (tray == null) {
			showTip = true;
			final Image image = Configuration.getImage(Configuration.Paths.Resources.ICON);
			tray = new TrayIcon(image, Configuration.NAME, null);
			tray.setImageAutoSize(true);
			tray.addMouseListener(new MouseListener() {
				public void mouseClicked(MouseEvent arg0) {
				}

				public void mouseEntered(MouseEvent arg0) {
				}

				public void mouseExited(MouseEvent arg0) {
				}

				public void mouseReleased(MouseEvent arg0) {
				}

				public void mousePressed(MouseEvent arg0) {
					SystemTray.getSystemTray().remove(tray);
					setVisible(true);
					lessCpu(false);
				}
			});
		}
		try {
			SystemTray.getSystemTray().add(tray);
			if (showTip) {
				tray.displayMessage(Configuration.NAME + " Hidden", "Bots are still running in the background.\nClick this icon to restore the window.", MessageType.INFO);
			}
		} catch (Exception ignored) {
			log.warning("Unable to hide window");
		}
		setVisible(false);
		lessCpu(true);
	}
}
