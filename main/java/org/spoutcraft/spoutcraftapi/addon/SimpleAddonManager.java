/*
 * This file is part of Spoutcraft API (http://wiki.getspout.org/).
 * 
 * Spoutcraft API is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Spoutcraft API is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.spoutcraft.spoutcraftapi.addon;

import java.io.File;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.util.FileUtil;
import org.spoutcraft.spoutcraftapi.Client;
import org.spoutcraft.spoutcraftapi.addon.java.JavaAddonLoader;
import org.spoutcraft.spoutcraftapi.command.AddonCommandYamlParser;
import org.spoutcraft.spoutcraftapi.command.Command;
import org.spoutcraft.spoutcraftapi.command.SimpleCommandMap;
import org.spoutcraft.spoutcraftapi.event.Event;
import org.spoutcraft.spoutcraftapi.event.HandlerList;
import org.spoutcraft.spoutcraftapi.event.Listener;

public class SimpleAddonManager implements AddonManager {

	private Client client;
	private final Map<Pattern, AddonLoader> fileAssociations = new HashMap<Pattern, AddonLoader>();
	private final List<Addon> addons = new ArrayList<Addon>();
	private final Map<String, Addon> lookupNames = new HashMap<String, Addon>();
	private static File updateDirectory = null;
	private final SimpleCommandMap commandMap;
	private final SimpleSecurityManager securityManager;
	private final double superSecretSecurityKey;

	public SimpleAddonManager(Client instance, SimpleCommandMap commandMap) {
		client = instance;
		this.commandMap = commandMap;
		
		superSecretSecurityKey = (new Random()).nextDouble();
		securityManager = new SimpleSecurityManager(superSecretSecurityKey);
		System.setSecurityManager(securityManager);
	}

	/**
	 * Registers the specified addon loader
	 * 
	 * @param loader Class name of the AddonLoader to register
	 * @throws IllegalArgumentException Thrown when the given Class is not a valid AddonLoader
	 */
	public void registerInterface(Class<? extends AddonLoader> loader) throws IllegalArgumentException {
		if (!loader.equals(JavaAddonLoader.class)){
			throw new UnsupportedOperationException("Spoutcraft does not currently support non-standard addon loaders. :(");
		}
		AddonLoader instance;

		if (AddonLoader.class.isAssignableFrom(loader)) {
			Constructor<? extends AddonLoader> constructor;

			try {
				constructor = loader.getConstructor(Client.class);
				instance = constructor.newInstance(client, securityManager, superSecretSecurityKey);
			} catch (NoSuchMethodException ex) {
				String className = loader.getName();

				throw new IllegalArgumentException(String.format("Class %s does not have a public %s(Spoutcraft) constructor", className, className), ex);
			} catch (Exception ex) {
				throw new IllegalArgumentException(String.format("Unexpected exception %s while attempting to construct a new instance of %s", ex.getClass().getName(), loader.getName()), ex);
			}
		} else {
			throw new IllegalArgumentException(String.format("Class %s does not implement interface AddonLoader", loader.getName()));
		}

		Pattern[] patterns = instance.getAddonFileFilters();

		synchronized (this) {
			for (Pattern pattern : patterns) {
				fileAssociations.put(pattern, instance);
			}
		}
	}
	
	/**
	 * Loads the addons contained within the specified directory
	 * 
	 * @param directory Directory to check for addons
	 * @return A list of all addons loaded
	 */
	public Addon[] loadAddons(File directory) {
		List<Addon> result = new ArrayList<Addon>();
		File[] files = directory.listFiles();

		boolean allFailed = false;
		boolean finalPass = false;

		LinkedList<File> filesList = new LinkedList<File>(Arrays.asList(files));

		if (!(client.getUpdateFolder() == null)) {
			updateDirectory = client.getUpdateFolder();
		}

		while (!allFailed || finalPass) {
			allFailed = true;
			Iterator<File> itr = filesList.iterator();

			while (itr.hasNext()) {
				File file = itr.next();
				Addon addon = null;

				try {
					addon = loadAddon(file, finalPass);
					itr.remove();
				} catch (UnknownDependencyException ex) {
					if (finalPass) {
						safelyLog(Level.SEVERE, "Could not load '" + file.getPath() + "' in folder '" + directory.getPath() + "': " + ex.getMessage(), ex);
						itr.remove();
					} else {
						addon = null;
					}
				} catch (InvalidAddonException ex) {
					safelyLog(Level.SEVERE, "Could not load '" + file.getPath() + "' in folder '" + directory.getPath() + "': ", ex.getCause());
					itr.remove();
				} catch (InvalidDescriptionException ex) {
					safelyLog(Level.SEVERE, "Could not load '" + file.getPath() + "' in folder '" + directory.getPath() + "': " + ex.getMessage(), ex);
					itr.remove();
				}

				if (addon != null) {
					result.add(addon);
					allFailed = false;
					finalPass = false;
				}
			}
			if (finalPass) {
				break;
			} else if (allFailed) {
				finalPass = true;
			}
		}

		return result.toArray(new Addon[result.size()]);
	}

	/**
	 * Loads the addon in the specified file
	 * 
	 * File must be valid according to the current enabled Addon interfaces
	 * 
	 * @param file File containing the addon to load
	 * @return The Addon loaded, or null if it was invalid
	 * @throws InvalidAddonException Thrown when the specified file is not a valid addon
	 * @throws InvalidDescriptionException Thrown when the specified file contains an invalid description
	 */
	public synchronized Addon loadAddon(File file) throws InvalidAddonException, InvalidDescriptionException, UnknownDependencyException {
		return loadAddon(file, true);
	}

	/**
	 * Loads the addon in the specified file
	 * 
	 * File must be valid according to the current enabled Addon interfaces
	 * 
	 * @param file File containing the addon to load
	 * @param ignoreSoftDependencies Loader will ignore soft dependencies if this flag is set to true
	 * @return The Addon loaded, or null if it was invalid
	 * @throws InvalidAddonException Thrown when the specified file is not a valid addon
	 * @throws InvalidDescriptionException Thrown when the specified file contains an invalid description
	 */
	public synchronized Addon loadAddon(File file, boolean ignoreSoftDependencies) throws InvalidAddonException, InvalidDescriptionException, UnknownDependencyException {
		securityManager.lock(superSecretSecurityKey);
		File updateFile = null;

		if (updateDirectory != null && updateDirectory.isDirectory() && (updateFile = new File(updateDirectory, file.getName())).isFile()) {
			if (FileUtil.copy(updateFile, file)) {
				updateFile.delete();
			}
		}

		Set<Pattern> filters = fileAssociations.keySet();
		Addon result = null;

		for (Pattern filter : filters) {
			String name = file.getName();
			Matcher match = filter.matcher(name);

			if (match.find()) {
				AddonLoader loader = fileAssociations.get(filter);

				result = loader.loadAddon(file, ignoreSoftDependencies);
			}
		}

		if (result != null) {
			addons.add(result);
			lookupNames.put(result.getDescription().getName(), result);
		}
		securityManager.unlock(superSecretSecurityKey);
		return result;
	}

	/**
	 * Checks if the given addon is loaded and returns it when applicable
	 * 
	 * Please note that the name of the addon is case-sensitive
	 * 
	 * @param name Name of the addon to check
	 * @return Addon if it exists, otherwise null
	 */
	public synchronized Addon getAddon(String name) {
		return lookupNames.get(name);
	}

	public synchronized Addon[] getAddons() {
		return addons.toArray(new Addon[0]);
	}

	/**
	 * Checks if the given addon is enabled or not
	 * 
	 * Please note that the name of the addon is case-sensitive.
	 * 
	 * @param name Name of the addon to check
	 * @return true if the addon is enabled, otherwise false
	 */
	public boolean isAddonEnabled(String name) {
		Addon addon = getAddon(name);

		return isAddonEnabled(addon);
	}

	/**
	 * Checks if the given addon is enabled or not
	 * 
	 * @param addon Addon to check
	 * @return true if the addon is enabled, otherwise false
	 */
	public boolean isAddonEnabled(Addon addon) {
		if ((addon != null) && (addons.contains(addon))) {
			return addon.isEnabled();
		} else {
			return false;
		}
	}

	public void enableAddon(final Addon addon) {
		if (addon instanceof ServerAddon) {
			return;
		}
		if (!addon.isEnabled()) {
			securityManager.lock(superSecretSecurityKey);
			List<Command> addonCommands = AddonCommandYamlParser.parse(addon);

			if (!addonCommands.isEmpty()) {
				commandMap.registerAll(addon.getDescription().getName(), addonCommands);
			}

			try {
				addon.getAddonLoader().enableAddon(addon);
			} catch (Throwable ex) {
				safelyLog(Level.SEVERE, "Error occurred (in the addon loader) while enabling " + addon.getDescription().getFullName() + " (Is it up to date?): " + ex.getMessage(), ex);
			}
			securityManager.unlock(superSecretSecurityKey);
		}
	}

	public void disableAddons() {
		for (Addon addon : getAddons()) {
			disableAddon(addon);
		}
	}

	public void disableAddon(final Addon addon) {
		if (addon instanceof ServerAddon) {
			return;
		}
		if (addon.isEnabled()) {
			securityManager.lock(superSecretSecurityKey);
			try {
				addon.getAddonLoader().disableAddon(addon);
			} catch (Throwable ex) {
				safelyLog(Level.SEVERE, "Error occurred (in the addon loader) while disabling " + addon.getDescription().getFullName() + " (Is it up to date?): " + ex.getMessage(), ex);
			}
			securityManager.unlock(superSecretSecurityKey);
		}
	}

	public void clearAddons() {
		synchronized (this) {
			disableAddons();
			addons.clear();
			lookupNames.clear();
			HandlerList.clearAll();
		}
	}

	/**
	 * Call an event.
	 * 
	 * @param <TEvent> Event subclass
	 * @param event Event to handle
	 * @author lahwran
	 */
	public <TEvent extends Event<TEvent>> void callEvent(TEvent event) {
		
		HandlerList<TEvent> handlerlist = event.getHandlers();
		handlerlist.bake();

		Listener<TEvent>[][] handlers = handlerlist.handlers;
		int[] handlerids = handlerlist.handlerids;
		
		securityManager.lock(superSecretSecurityKey);

		for (int arrayidx = 0; arrayidx < handlers.length; arrayidx++) {

			// if the order slot is even and the event has stopped propogating
			if (event.isCancelled() && (handlerids[arrayidx] & 1) == 0)
				continue; // then don't call this order slot

			for (int handler = 0; handler < handlers[arrayidx].length; handler++) {
				try {
					
					handlers[arrayidx][handler].onEvent(event);

				} catch (Throwable t) {
					System.err.println("Error while passing event " + event);
					t.printStackTrace();
				}
			}
		}
		securityManager.unlock(superSecretSecurityKey);
	}
	
	private void safelyLog(Level level, String message, Throwable ex) {
		boolean relock = false;
		if (securityManager.isLocked()){
			relock = true;
			securityManager.unlock(superSecretSecurityKey);
		}
		client.getLogger().log(level, message, ex);
		if (relock) {
			securityManager.lock(superSecretSecurityKey);
		}
	}
	
	public void addFakeAddon(ServerAddon addon) {
		addons.add(addon);
		addon.setEnabled(true);
		lookupNames.put(addon.getDescription().getName(), addon);
	}

}
