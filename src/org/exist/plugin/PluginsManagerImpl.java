/*
 *  eXist Open Source Native XML Database
 *  Copyright (C) 2010-2011 The eXist Project
 *  http://exist-db.org
 *  
 *  This program is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public License
 *  as published by the Free Software Foundation; either version 2
 *  of the License, or (at your option) any later version.
 *  
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Lesser General Public License for more details.
 *  
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 *  
 *  $Id$
 */
package org.exist.plugin;

import java.io.*;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.util.*;

import org.apache.log4j.Logger;
import org.exist.Database;
import org.exist.EXistException;
import org.exist.backup.BackupHandler;
import org.exist.backup.RestoreHandler;
import org.exist.collections.Collection;
import org.exist.config.*;
import org.exist.config.annotation.*;
import org.exist.dom.DocumentAtExist;
import org.exist.storage.DBBroker;
import org.exist.storage.txn.TransactionManager;
import org.exist.storage.txn.Txn;
import org.exist.util.serializer.SAXSerializer;
import org.exist.xmldb.XmldbURI;
import org.xml.sax.Attributes;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;

/**
 * Plugins manager. 
 * It control search procedure, activation and de-actication (including runtime).
 * 
 * @author <a href="mailto:shabanovd@gmail.com">Dmitriy Shabanov</a>
 * 
 */
@ConfigurationClass("plugin-manager")
public class PluginsManagerImpl implements Configurable, PluginsManager, Startable {

	private final static Logger LOG = Logger.getLogger(PluginsManagerImpl.class);

	public final static XmldbURI PLUGINS_COLLETION_URI = XmldbURI.SYSTEM_COLLECTION_URI.append("plugins");
	public final static XmldbURI CONFIG_FILE_URI = XmldbURI.create("config.xml");

	@ConfigurationFieldAsAttribute("version")
	private String version = "1.0";

	@ConfigurationFieldAsElement("plugin")
	private List<String> runPlugins = new ArrayList<String>();

//	@ConfigurationFieldAsElement("search-path")
//	private Map<String, File> placesToSearch = new LinkedHashMap<String, File>();

//	private Map<String, PluginInfo> foundClasses = new LinkedHashMap<String, PluginInfo>();
	
	private Map<String, Jack> jacks = new HashMap<String, Jack>();
	
	private Configuration configuration = null;
	
	private Collection collection;
	
	private Database db;
	
	public PluginsManagerImpl(Database db, DBBroker broker) throws ConfigurationException {
		this.db = db;
		
		
		//Temporary for testing
		addPlugin("org.exist.storage.md.Plugin");
		
	}

	@Override
	public void startUp(DBBroker broker) throws EXistException {
        TransactionManager transaction = db.getTransactionManager();
        Txn txn = null;

        try {
	        collection = broker.getCollection(PLUGINS_COLLETION_URI);
			if (collection == null) {
				txn = transaction.beginTransaction();
				collection = broker.getOrCreateCollection(txn, PLUGINS_COLLETION_URI);
				if (collection == null) return;
					//if db corrupted it can lead to unrunnable issue
					//throw new ConfigurationException("Collection '/db/system/plugins' can't be created.");
				
				collection.setPermissions(0770);
				broker.saveCollection(txn, collection);

				transaction.commit(txn);
			} 
        } catch (Exception e) {
			transaction.abort(txn);
			e.printStackTrace();
			LOG.debug("loading configuration failed: " + e.getMessage());
		}

        Configuration _config_ = Configurator.parse(this, broker, collection, CONFIG_FILE_URI);
		configuration = Configurator.configure(this, _config_);
		
		//load plugins by META-INF/services/
		try {
//			File libFolder = new File(((BrokerPool)db).getConfiguration().getExistHome(), "lib");
//			File pluginsFolder = new File(libFolder, "plugins");
//			placesToSearch.put(pluginsFolder.getAbsolutePath(), pluginsFolder);
			
			for (Class<? extends Jack> plugin : listServices(Jack.class)) {
				//System.out.println("found plugin "+plugin);
				
				try {
					Constructor<? extends Jack> ctor = plugin.getConstructor(PluginsManager.class);
					Jack plgn = ctor.newInstance(this);
					
					jacks.put(plugin.getName(), plgn);
				} catch (Throwable e) {
					e.printStackTrace();
				}
			}
		} catch (Throwable e) {
			e.printStackTrace();
		}
		//UNDERSTAND: call save?

//		try {
//			configuration.save(broker);
//		} catch (PermissionDeniedException e) {
//			//LOG?
//		}
	}
	
	
	public String version() {
		return version;
	}
	
	@SuppressWarnings("unchecked")
	public void addPlugin(String className) {
		//check if already run
		if (jacks.containsKey(className))
			return;
		
		try {
			Class<? extends Jack> plugin = (Class<? extends Jack>) Class.forName(className);
			
			Constructor<? extends Jack> ctor = plugin.getConstructor(PluginsManager.class);
			Jack plgn = ctor.newInstance(this);
			
			jacks.put(plugin.getName(), plgn);

			runPlugins.add(className);
		} catch (Throwable e) {
			//e.printStackTrace();
		}
	}
	
	public void sync() {
		for (Jack plugin : jacks.values()) {
			try {
				plugin.sync();
			} catch (Throwable e) {
				LOG.error(e);
			}
		}
	}

	public void shutdown() {
		for (Jack plugin : jacks.values()) {
			try {
				plugin.stop();
			} catch (Throwable e) {
				LOG.error(e);
			}
		}
	}
	
	public Database getDatabase() {
		return db;
	}
	
	/*
	 * Generate list of service implementations 
	 */
	private <S> Iterable<Class<? extends S>> listServices(Class<S> ifc) throws Exception {
		ClassLoader ldr = Thread.currentThread().getContextClassLoader();
		Enumeration<URL> e = ldr.getResources("META-INF/services/" + ifc.getName());
		Set<Class<? extends S>> services = new HashSet<Class<? extends S>>();
		while (e.hasMoreElements()) {
			URL url = e.nextElement();
			InputStream is = url.openStream();
			try {
				BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
				while (true) {
					String line = r.readLine();
					if (line == null)
						break;
					int comment = line.indexOf('#');
					if (comment >= 0)
						line = line.substring(0, comment);
					String name = line.trim();
					if (name.length() == 0)
						continue;
					Class<?> clz = Class.forName(name, true, ldr);
					Class<? extends S> impl = clz.asSubclass(ifc);
					services.add(impl);
				}
			} finally {
				is.close();
			}
		}
		return services;
	}

	@Override
	public boolean isConfigured() {
		return configuration != null;
	}

	@Override
	public Configuration getConfiguration() {
		return configuration;
	}
	
	private BackupHandler bh = new BH();

	@Override
	public BackupHandler getBackupHandler() {
		return bh;
	}
	
	class BH implements BackupHandler {

		@Override
		public void backup(Collection colection, AttributesImpl attrs) {
			for (Jack plugin : jacks.values()) {
				if (plugin instanceof BackupHandler) {
					((BackupHandler) plugin).backup(colection, attrs);
				}
			}
		}

		@Override
		public void backup(Collection colection, SAXSerializer serializer) throws SAXException {
			for (Jack plugin : jacks.values()) {
				if (plugin instanceof BackupHandler) {
					((BackupHandler) plugin).backup(colection, serializer);
				}
			}
		}

		@Override
		public void backup(DocumentAtExist document, AttributesImpl attrs) {
			for (Jack plugin : jacks.values()) {
				if (plugin instanceof BackupHandler) {
					((BackupHandler) plugin).backup(document, attrs);
				}
			}
		}

		@Override
		public void backup(DocumentAtExist document, SAXSerializer serializer) throws SAXException {
			for (Jack plugin : jacks.values()) {
				if (plugin instanceof BackupHandler) {
					((BackupHandler) plugin).backup(document, serializer);
				}
			}
		}
	}

	private RestoreHandler rh = new RH();

	@Override
	public RestoreHandler getRestoreHandler() {
		return rh;
	}

	class RH implements RestoreHandler {

		@Override
		public void setDocumentLocator(Locator locator) {
			for (Jack plugin : jacks.values()) {
				if (plugin instanceof RestoreHandler) {
					((RestoreHandler) plugin).setDocumentLocator(locator);
				}
			}
		}

		@Override
		public void startDocument() throws SAXException {
			for (Jack plugin : jacks.values()) {
				if (plugin instanceof RestoreHandler) {
					((RestoreHandler) plugin).startDocument();
				}
			}
		}

		@Override
		public void endDocument() throws SAXException {
			for (Jack plugin : jacks.values()) {
				if (plugin instanceof RestoreHandler) {
					((RestoreHandler) plugin).endDocument();
				}
			}
		}

		@Override
		public void startPrefixMapping(String prefix, String uri) throws SAXException {
			for (Jack plugin : jacks.values()) {
				if (plugin instanceof RestoreHandler) {
					((RestoreHandler) plugin).startPrefixMapping(prefix, uri);
				}
			}
		}

		@Override
		public void endPrefixMapping(String prefix) throws SAXException {
			for (Jack plugin : jacks.values()) {
				if (plugin instanceof RestoreHandler) {
					((RestoreHandler) plugin).endPrefixMapping(prefix);
				}
			}
		}

		@Override
		public void startElement(String uri, String localName, String qName, Attributes atts) throws SAXException {
			for (Jack plugin : jacks.values()) {
				if (plugin instanceof RestoreHandler) {
					((RestoreHandler) plugin).startElement(uri, localName, qName, atts);
				}
			}
		}

		@Override
		public void endElement(String uri, String localName, String qName) throws SAXException {
			for (Jack plugin : jacks.values()) {
				if (plugin instanceof RestoreHandler) {
					((RestoreHandler) plugin).endElement(uri, localName, qName);
				}
			}
		}

		@Override
		public void characters(char[] ch, int start, int length) throws SAXException {
			for (Jack plugin : jacks.values()) {
				if (plugin instanceof RestoreHandler) {
					((RestoreHandler) plugin).characters(ch, start, length);
				}
			}
		}

		@Override
		public void ignorableWhitespace(char[] ch, int start, int length) throws SAXException {
			for (Jack plugin : jacks.values()) {
				if (plugin instanceof RestoreHandler) {
					((RestoreHandler) plugin).ignorableWhitespace(ch, start, length);
				}
			}
		}

		@Override
		public void processingInstruction(String target, String data) throws SAXException {
			for (Jack plugin : jacks.values()) {
				if (plugin instanceof RestoreHandler) {
					((RestoreHandler) plugin).processingInstruction(target, data);
				}
			}
		}

		@Override
		public void skippedEntity(String name) throws SAXException {
			for (Jack plugin : jacks.values()) {
				if (plugin instanceof RestoreHandler) {
					((RestoreHandler) plugin).skippedEntity(name);
				}
			}
		}

		@Override
		public void startCollectionRestore(Collection colection, Attributes atts) {
			for (Jack plugin : jacks.values()) {
				if (plugin instanceof RestoreHandler) {
					((RestoreHandler) plugin).startCollectionRestore(colection, atts);
				}
			}
		}

		@Override
		public void endCollectionRestore(Collection colection) {
			for (Jack plugin : jacks.values()) {
				if (plugin instanceof RestoreHandler) {
					((RestoreHandler) plugin).endCollectionRestore(colection);
				}
			}
		}

		@Override
		public void startDocumentRestore(DocumentAtExist document, Attributes atts) {
			for (Jack plugin : jacks.values()) {
				if (plugin instanceof RestoreHandler) {
					((RestoreHandler) plugin).startDocumentRestore(document, atts);
				}
			}
		}

		@Override
		public void endDocumentRestore(DocumentAtExist document) {
			for (Jack plugin : jacks.values()) {
				if (plugin instanceof RestoreHandler) {
					((RestoreHandler) plugin).endDocumentRestore(document);
				}
			}
		}
	}
}