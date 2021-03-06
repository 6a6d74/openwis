//==============================================================================
//===
//===   JeevesEngine
//===
//=============================================================================
//===	Copyright (C) 2001-2005 Food and Agriculture Organization of the
//===	United Nations (FAO-UN), United Nations World Food Programme (WFP)
//===	and United Nations Environment Programme (UNEP)
//===
//===	This library is free software; you can redistribute it and/or
//===	modify it under the terms of the GNU Lesser General Public
//===	License as published by the Free Software Foundation; either
//===	version 2.1 of the License, or (at your option) any later version.
//===
//===	This library is distributed in the hope that it will be useful,
//===	but WITHOUT ANY WARRANTY; without even the implied warranty of
//===	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
//===	Lesser General Public License for more details.
//===
//===	You should have received a copy of the GNU Lesser General Public
//===	License along with this library; if not, write to the Free Software
//===	Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
//===
//===	Contact: Jeroen Ticheler - FAO - Viale delle Terme di Caracalla 2,
//===	Rome - Italy. email: GeoNetwork@fao.org
//==============================================================================

package jeeves.server;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;

import javax.servlet.ServletException;
import javax.xml.transform.TransformerFactory;

import jeeves.constants.ConfigFile;
import jeeves.constants.Jeeves;
import jeeves.exceptions.BadInputEx;
import jeeves.interfaces.Activator;
import jeeves.interfaces.ApplicationHandler;
import jeeves.interfaces.Logger;
import jeeves.server.context.ServiceContext;
import jeeves.server.dispatchers.ServiceManager;
import jeeves.server.resources.ProviderManager;
import jeeves.server.sources.ServiceRequest;
import jeeves.server.sources.http.JeevesServlet;
import jeeves.utils.Log;
import jeeves.utils.SerialFactory;
import jeeves.utils.Util;
import jeeves.utils.Xml;
import net.sf.saxon.TransformerFactoryImpl;

import org.apache.log4j.PropertyConfigurator;
import org.jdom.Element;

//=============================================================================

/** This is the main class. It handles http connections and inits the system
  */

public class JeevesEngine
{
	private String  defaultSrv;
	private String  profilesFile;
	private String  defaultLang;
	private String  defaultContType;
	private String  uploadDir;
	private int     maxUploadSize;
	private String  appPath;
	private boolean defaultLocal;
	private String portalType;
	private boolean debugFlag;

	/** true if the 'general' part has been loaded */
	private boolean generalLoaded;

	private final ServiceManager  serviceMan  = new ServiceManager();
	private final ProviderManager providerMan = new ProviderManager();
	private final ScheduleManager scheduleMan = new ScheduleManager();
	private final SerialFactory   serialFact  = new SerialFactory();

	private final Logger appHandLogger = Log.createLogger(Log.APPHAND);
	private final List<Element> appHandList = new ArrayList<Element>();
	private final Vector<ApplicationHandler> vAppHandlers = new Vector<ApplicationHandler>();
	private final Vector<Activator> vActivators = new Vector<Activator>();

	//---------------------------------------------------------------------------
	//---
	//--- Init
	//---
	//---------------------------------------------------------------------------

	/** Inits the engine, loading all needed data
	  */

	public void init(String appPath, String configPath, String baseUrl, JeevesServlet servlet) throws ServletException
	{
		try
		{
			this.appPath = appPath;

			long start   = System.currentTimeMillis();

			long maxMem  = Runtime.getRuntime().maxMemory()   / 1024;
			long totMem  = Runtime.getRuntime().totalMemory() / 1024;
			long freeMem = Runtime.getRuntime().freeMemory()  / 1024;

			long usedMem      = totMem - freeMem;
			long startFreeMem = maxMem - usedMem;

			PropertyConfigurator.configure(configPath +"log4j.cfg");

			// System.setProperty("javax.xml.transform.TransformerFactory",
			//						 "net.sf.saxon.TransformerFactoryImpl");
			// Do this using library meta-inf to avoid affecting other servlets
			// in the same container

			info("=== Starting system ========================================");

			//---------------------------------------------------------------------
			//--- init system

			info("Java version : "+ System.getProperty("java.vm.version"));
			info("Java vendor  : "+ System.getProperty("java.vm.vendor"));
         info("XSLT factory Saxon: "
               + new TransformerFactoryImpl().newTransformer().getClass().getName());

         info("XSLT factory Default: "
               + TransformerFactory.newInstance().newTransformer().getClass().getName());

			info("Path    : "+ appPath);
			info("BaseURL : "+ baseUrl);

			serviceMan.setAppPath(appPath);
			serviceMan.setProviderMan(providerMan);
			serviceMan.setSerialFactory(serialFact);
			serviceMan.setBaseUrl(baseUrl);
			serviceMan.setServlet(servlet);

			scheduleMan.setAppPath(appPath);
			scheduleMan.setProviderMan(providerMan);
			scheduleMan.setSerialFactory(serialFact);
			scheduleMan.setBaseUrl(baseUrl);

			loadConfigFile(configPath, Jeeves.CONFIG_FILE, serviceMan);

			info("Initializing profiles...");
			serviceMan.loadProfiles(profilesFile);

			//--- handlers must be started here because they may need the context
			//--- with the ProfileManager already loaded

			for(int i=0; i<appHandList.size(); i++)
				initAppHandler(appHandList.get(i), servlet);

			info("Starting schedule manager...");
			scheduleMan.start();

			//---------------------------------------------------------------------

			long end      = System.currentTimeMillis();
			long duration = (end - start) / 1000;

			freeMem = Runtime.getRuntime().freeMemory()  / 1024;
			totMem  = Runtime.getRuntime().totalMemory() / 1024;
			usedMem = totMem - freeMem;

			long endFreeMem = maxMem - usedMem;
			long dataMem    = startFreeMem - endFreeMem;

			info("Memory used is  : " + dataMem  + " Kb");
			info("Total memory is : " + maxMem   + " Kb");
			info("Startup time is : " + duration + " (secs)");

			info("=== System working =========================================");
		}
		catch (Exception e)
		{
			fatal("Raised exception during init");
			fatal("   Exception : " +e);
			fatal("   Message   : " +e.getMessage());
			fatal("   Stack     : " +Util.getStackTrace(e));

			throw new ServletException("Exception raised", e);
		}
	}

	//---------------------------------------------------------------------------

	@SuppressWarnings("unchecked")
   private void loadConfigFile(String path, String file, ServiceManager serviceMan) throws Exception
	{
		file = path + file;

		info("Loading : " + file);

		Element configRoot = Xml.loadFile(file);

		Element elGeneral = configRoot.getChild(ConfigFile.Child.GENERAL);
		Element elDefault = configRoot.getChild(ConfigFile.Child.DEFAULT);

		if (!generalLoaded)
		{
			if (elGeneral == null)
				throw new NullPointerException("Missing 'general' element in config file :" +file);

			if (elDefault == null)
				throw new NullPointerException("Missing 'default' element in config file :" +file);

			generalLoaded = true;

			initGeneral(elGeneral, serviceMan);
			initDefault(elDefault, serviceMan);
		}
		else
		{
			if (elGeneral != null)
				throw new IllegalArgumentException("Illegal 'general' element in secondary include");

			if (elDefault != null)
				throw new IllegalArgumentException("Illegal 'default' element in secondary include");
		}

		//--- init resources

		List<Element> resList = configRoot.getChildren(ConfigFile.Child.RESOURCES);

		for(int i=0; i<resList.size(); i++)
			initResources(resList.get(i));

		//--- init app-handlers

		appHandList.addAll(configRoot.getChildren(ConfigFile.Child.APP_HANDLER));

		//--- init services

		List<Element> srvList = configRoot.getChildren(ConfigFile.Child.SERVICES);

		for(int i=0; i<srvList.size(); i++)
			initServices(srvList.get(i));

		//--- init schedules

		List<Element> schedList = configRoot.getChildren(ConfigFile.Child.SCHEDULES);

		for(int i=0; i<schedList.size(); i++)
			initSchedules(schedList.get(i));

		//--- recurse on includes

		List<Element> includes = configRoot.getChildren(ConfigFile.Child.INCLUDE);

		for(int i=0; i<includes.size(); i++)
		{
			Element include = includes.get(i);

			loadConfigFile(path, include.getText(), serviceMan);
		}
	}

	//---------------------------------------------------------------------------
	//---
	//--- 'general' element
	//---
	//---------------------------------------------------------------------------

	/** Setup parameters from config tag (config.xml)
	  */

	private void initGeneral(Element general, ServiceManager serviceMan) throws BadInputEx
	{
		info("Initializing general configuration...");

		profilesFile = Util.getParam(general, ConfigFile.General.Child.PROFILES);
		uploadDir    = Util.getParam(general, ConfigFile.General.Child.UPLOAD_DIR);
		try {
		    maxUploadSize = Integer.parseInt(Util.getParam(general, ConfigFile.General.Child.MAX_UPLOAD_SIZE));
		}
		catch(Exception e){
		    maxUploadSize = 50;
		    error("Maximum upload size not properly configured in config.xml. Using default size of 50MB");
            error("   Exception : " +e);
            error("   Message   : " +e.getMessage());
            error("   Stack     : " +Util.getStackTrace(e));
	    }

		if (!new File(uploadDir).isAbsolute())
			uploadDir = appPath + uploadDir;

		if (!uploadDir.endsWith("/"))
			uploadDir += "/";

		new File(uploadDir).mkdirs();

		debugFlag = "true".equals(general.getChildText(ConfigFile.General.Child.DEBUG));

		serviceMan.setUploadDir(uploadDir);
		serviceMan.setMaxUploadSize(maxUploadSize);
	}

	//---------------------------------------------------------------------------
	//---
	//--- 'general' element
	//---
	//---------------------------------------------------------------------------

	/** Setup parameters from config tag (config.xml)
	  */

	@SuppressWarnings("unchecked")
   private void initDefault(Element defaults, ServiceManager serviceMan) throws Exception
	{
		info("Initializing defaults...");

		defaultSrv     = Util.getParam(defaults, ConfigFile.Default.Child.SERVICE);
		defaultLang    = Util.getParam(defaults, ConfigFile.Default.Child.LANGUAGE);
		defaultContType= Util.getParam(defaults, ConfigFile.Default.Child.CONTENT_TYPE);
		portalType     = Util.getParam(defaults, ConfigFile.Default.Child.PORTAL_TYPE);


		defaultLocal = "true".equals(defaults.getChildText(ConfigFile.Default.Child.LOCALIZED));
		info("   Default local is :" +defaultLocal);

		serviceMan.setDefaultLang(defaultLang);
		serviceMan.setDefaultLocal(defaultLocal);
		serviceMan.setDefaultContType(defaultContType);
      serviceMan.setPortalType(portalType);

		List<Element> errorPages = defaults.getChildren(ConfigFile.Default.Child.ERROR);

		for(int i=0; i<errorPages.size(); i++)
			serviceMan.addErrorPage(errorPages.get(i));

		Element gui = defaults.getChild(ConfigFile.Default.Child.GUI);

		if (gui != null)
		{
			List<Element> guiElems = gui.getChildren();

			for(int i=0; i<guiElems.size(); i++)
				serviceMan.addDefaultGui(guiElems.get(i));
		}
	}

	//---------------------------------------------------------------------------
	//---
	//--- 'resources' element
	//---
	//---------------------------------------------------------------------------

	/** Setup resources from the resource element (config.xml)
	  */

	@SuppressWarnings("unchecked")
   private void initResources(Element resources)
	{
		info("Initializing resources...");

		List<Element> resList = resources.getChildren(ConfigFile.Resources.Child.RESOURCE);

		for(int i=0; i<resList.size(); i++)
		{
			Element res = resList.get(i);

			String  name      = res.getChildText(ConfigFile.Resource.Child.NAME);
			String  provider  = res.getChildText(ConfigFile.Resource.Child.PROVIDER);
			Element config    = res.getChild    (ConfigFile.Resource.Child.CONFIG);
			Element activator = res.getChild    (ConfigFile.Resource.Child.ACTIVATOR);

			String enabled = res.getAttributeValue(ConfigFile.Resource.Attr.ENABLED);

			if ((enabled == null) || enabled.equals("true"))
			{
				info("   Adding resource : " + name);

				try
				{
					if (activator != null)
					{
						String clas = activator.getAttributeValue(ConfigFile.Activator.Attr.CLASS);

						info("      Loading activator  : "+ clas);
						Activator activ = (Activator) Class.forName(clas).newInstance();

						info("      Starting activator : "+ clas);
						activ.startup(appPath, activator);

						vActivators.add(activ);
					}

					providerMan.register(provider, name, config);
				}
				catch(Exception e)
				{
					error("Raised exception while initializing resource. Skipped.");
					error("   Resource  : " +name);
					error("   Provider  : " +provider);
					error("   Exception : " +e);
					error("   Message   : " +e.getMessage());
					error("   Stack     : " +Util.getStackTrace(e));
				}
			}
		}
	}

	//---------------------------------------------------------------------------
	//---
	//--- 'appHandler' element
	//---
	//---------------------------------------------------------------------------

	@SuppressWarnings("unchecked")
   private void initAppHandler(Element handler, JeevesServlet servlet) throws Exception
	{
		if (handler == null)
			info("Handler not found");
		else
		{
			String className = handler.getAttributeValue(ConfigFile.AppHandler.Attr.CLASS);

			if (className == null)
				throw new IllegalArgumentException("Missing '"        +ConfigFile.AppHandler.Attr.CLASS+
															  "' attribute in '" +ConfigFile.Child.APP_HANDLER+
															  "' element");

			info("Found handler : " +className);

			Class<ApplicationHandler> c = (Class<ApplicationHandler>) Class.forName(className);

			ApplicationHandler h = c.newInstance();

			ServiceContext srvContext = serviceMan.createServiceContext("AppHandler");
			srvContext.setLanguage(defaultLang);
			srvContext.setLogger(appHandLogger);
			srvContext.setServlet(servlet);

			try
			{
				info("--- Starting handler --------------------------------------");

				Object context = h.start(handler, srvContext);

				srvContext.getResourceManager().close();
				vAppHandlers.add(h);
				serviceMan .registerContext(h.getContextName(), context);
				scheduleMan.registerContext(h.getContextName(), context);

				info("--- Handler started ---------------------------------------");
			}
			catch (Exception e)
			{
				error("Raised exception while starting appl handler. Skipped.");
				error("   Handler   : " +className);
				error("   Exception : " +e);
				error("   Message   : " +e.getMessage());
				error("   Stack     : " +Util.getStackTrace(e));

				srvContext.getResourceManager().abort();
			}
		}
	}

	//---------------------------------------------------------------------------
	//---
	//--- 'services' element
	//---
	//---------------------------------------------------------------------------

	/** Setup services found in the services tag (config.xml)
	  */

	@SuppressWarnings("unchecked")
   private void initServices(Element services) throws Exception
	{
		info("Initializing services...");

		//--- get services root package
		String pack = services.getAttributeValue(ConfigFile.Services.Attr.PACKAGE);

		// --- scan services elements
      for (Element service : (List<Element>) services
            .getChildren(ConfigFile.Services.Child.SERVICE)) {
         String name = service.getAttributeValue(ConfigFile.Service.Attr.NAME);

			info("   Adding service : " + name);

			try {
				serviceMan.addService(pack, service);
			} catch(Exception e) {
				warning("Raised exception while registering service. Skipped.");
				warning("   Service   : " + name);
				warning("   Package   : " + pack);
				warning("   Exception : " + e);
				warning("   Message   : " + e.getMessage());
				warning("   Stack     : " + Util.getStackTrace(e));
			}
		}
	}

	//---------------------------------------------------------------------------
	//---
	//--- 'schedules' element
	//---
	//---------------------------------------------------------------------------

	/** Setup schedules found in the 'schedules' element (config.xml)
	  */

	@SuppressWarnings("unchecked")
   private void initSchedules(Element schedules) throws Exception
	{
		info("Initializing schedules...");

		//--- get schedules root package
		String pack = schedules.getAttributeValue(ConfigFile.Schedules.Attr.PACKAGE);

		// --- scan schedules elements
      for (Element schedule : (List<Element>) schedules
            .getChildren(ConfigFile.Schedules.Child.SCHEDULE)) {
         String name = schedule.getAttributeValue(ConfigFile.Schedule.Attr.NAME);

         info("   Adding schedule : " + name);

         try {
            scheduleMan.addSchedule(pack, schedule);
         } catch (Exception e) {
            error("Raised exception while registering schedule. Skipped.");
            error("   Schedule  : " + name);
            error("   Package   : " + pack);
            error("   Exception : " + e);
            error("   Message   : " + e.getMessage());
            error("   Stack     : " + Util.getStackTrace(e));
         }
      }
	}

	//---------------------------------------------------------------------------
	//---
	//--- Destroy
	//---
	//---------------------------------------------------------------------------

	public void destroy()
	{
		try
		{
			info("=== Stopping system ========================================");

			info("Stopping schedule manager...");
			scheduleMan.exit();

			info("Stopping handlers...");
			stopHandlers();

			info("Stopping resources...");
			stopResources();

			info("=== System stopped ========================================");
		}
		catch (Exception e)
		{
			error("Raised exception during destroy");
			error("  Exception : " +e);
			error("  Message   : " +e.getMessage());
			error("  Stack     : " +Util.getStackTrace(e));
		}
	}

	//---------------------------------------------------------------------------
	/** Stop handlers
	  */
	private void stopHandlers() throws Exception {
	   for (ApplicationHandler h : vAppHandlers) {
	      h.stop();
		}
	}

	//---------------------------------------------------------------------------
	/** Stop resources
	  */

	private void stopResources()
	{
		providerMan.end();
		for (Activator a : vActivators) {
		   info("   Stopping activator : " + a.getClass().getName());
		   a.shutdown();
		}
	}

	//---------------------------------------------------------------------------
	//---
	//--- API methods
	//---
	//---------------------------------------------------------------------------

	public String getUploadDir() { return uploadDir; }

	//---------------------------------------------------------------------------

    public int getMaxUploadSize() { return maxUploadSize; }

    //---------------------------------------------------------------------------

	public void dispatch(ServiceRequest srvReq, UserSession session)
	{
		if (srvReq.getService() == null || srvReq.getService().length() == 0)
			srvReq.setService(defaultSrv);

		if (srvReq.getLanguage() == null || srvReq.getLanguage().length() == 0)
			srvReq.setLanguage(defaultLang);

      srvReq.setDebug(srvReq.hasDebug() && debugFlag);

		//--- normal dispatch pipeline

		serviceMan.dispatch(srvReq, session);
	}

	//---------------------------------------------------------------------------
	//---
	//--- Other private methods
	//---
	//---------------------------------------------------------------------------

	private void info   (String message) { Log.info   (Log.ENGINE, message); }
	private void warning(String message) { Log.warning(Log.ENGINE, message); }
	private void error  (String message) { Log.error  (Log.ENGINE, message); }
	private void fatal  (String message) { Log.fatal  (Log.ENGINE, message); }
}

//=============================================================================


