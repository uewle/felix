/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.felix.cm.impl;


import java.io.IOException;
import java.util.Dictionary;
import java.util.Hashtable;

import org.apache.felix.cm.PersistenceManager;
import org.osgi.framework.Constants;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.log.LogService;


/**
 * The <code>ConfigurationImpl</code> is the backend implementation of the
 * Configuration Admin Service Specification <i>Configuration object</i>
 * (section 104.4). Instances of this class are shared by multiple instances of
 * the {@link ConfigurationAdapter} class, whose instances are actually returned
 * to clients.
 */
class ConfigurationImpl extends ConfigurationBase
{

    /*
     * Concurrency note: There is a slight (but real) chance of a race condition
     * between a configuration update and a ManagedService[Factory] registration.
     * Per the specification a ManagedService must be called with configuration
     * or null when registered and a ManagedService must be called with currently
     * existing configuration when registered. Also the ManagedService[Factory]
     * must be updated when the configuration is updated.
     *
     * Consider now this situation of two threads T1 and T2:
     *
     *    T1. create and update configuration
     *      ConfigurationImpl.update persists configuration and sets field
     *      Thread preempted
     *
     *    T2. ManagedServiceUpdate constructor reads configuration
     *      Uses configuration already persisted by T1 for update
     *      Schedules task to update service with the configuration
     *
     *    T1. Runs again creating the UpdateConfiguration task with the
     *      configuration persisted before being preempted
     *      Schedules task to update service
     *
     *    Update Thread:
     *      Updates ManagedService with configuration prepared by T2
     *      Updates ManagedService with configuration prepared by T1
     *
     * The correct behaviour would be here, that the second call to update
     * would not take place. We cannot at this point in time easily fix
     * this issue. Also, it seems that changes for this to happen are
     * small.
     *
     * This class provides modification counter (lastModificationTime)
     * which is incremented on each change of the configuration. This
     * helps the update tasks in the ConfigurationManager to log the
     * revision of the configuration supplied.
     */

    /**
     * The name of a synthetic property stored in the persisted configuration
     * data to indicate that the configuration data is new, that is created but
     * never updated (value is "_felix_.cm.newConfiguration").
     * <p>
     * This special property is stored by the
     * {@link #ConfigurationImpl(ConfigurationManager, PersistenceManager, String, String, String)}
     * constructor, when the configuration is first created and persisted and is
     * interpreted by the
     * {@link #ConfigurationImpl(ConfigurationManager, PersistenceManager, Dictionary)}
     * method when the configuration data is loaded in a new object.
     * <p>
     * The goal of this property is to keep the information on whether
     * configuration data is new (but persisted as per the spec) or has already
     * been assigned with possible no data.
     */
    private static final String CONFIGURATION_NEW = "_felix_.cm.newConfiguration";

    /**
     * The factory PID of this configuration or <code>null</code> if this
     * is not a factory configuration.
     */
    private final String factoryPID;

    /**
     * The statically bound bundle location, which is set explicitly by calling
     * the Configuration.setBundleLocation(String) method or when the
     * configuration was created with the two-argument method.
     */
    private volatile String staticBundleLocation;

    /**
     * The bundle location from dynamic binding. This value is set as the
     * configuration or factory is assigned to a ManagedService[Factory].
     */
    private volatile String dynamicBundleLocation;

    /**
     * The configuration data of this configuration instance. This is a private
     * copy of the properties of which a copy is made when the
     * {@link #getProperties()} method is called. This field is
     * <code>null</code> if the configuration has been created and never been
     * updated with acutal configuration properties.
     */
    private volatile CaseInsensitiveDictionary properties;

    /**
     * Flag indicating that this configuration has been deleted.
     *
     * @see #isDeleted()
     */
    private volatile boolean isDeleted;

    /**
     * Configuration revision counter incremented each time the
     * {@link #properties} is set (in the constructor or the
     * {@link #configure(Dictionary)} method. This counter is transient
     * and not persisted. Thus it is restarted from zero each time
     * an instance of this class is created.
     */
    private volatile long revision;


    ConfigurationImpl( ConfigurationManager configurationManager, PersistenceManager persistenceManager,
        Dictionary properties )
    {
        super( configurationManager, persistenceManager, ( String ) properties.remove( Constants.SERVICE_PID ) );

        this.factoryPID = ( String ) properties.remove( ConfigurationAdmin.SERVICE_FACTORYPID );
        this.isDeleted = false;

        // set bundle location from persistence and/or check for dynamic binding
        this.staticBundleLocation = ( String ) properties.remove( ConfigurationAdmin.SERVICE_BUNDLELOCATION ) ;
        this.dynamicBundleLocation = configurationManager.getDynamicBundleLocation( getBaseId() );

        // set the properties internally
        configureFromPersistence( properties );
    }


    ConfigurationImpl( ConfigurationManager configurationManager, PersistenceManager persistenceManager, String pid,
        String factoryPid, String bundleLocation ) throws IOException
    {
        super( configurationManager, persistenceManager, pid );

        this.factoryPID = factoryPid;
        this.isDeleted = false;

        // set bundle location from persistence and/or check for dynamic binding
        this.staticBundleLocation = bundleLocation;
        this.dynamicBundleLocation = configurationManager.getDynamicBundleLocation( getBaseId() );

        // first "update"
        this.properties = null;
        this.revision = 1;

        // this is a new configuration object, store immediately unless
        // the new configuration object is created from a factory, in which
        // case the configuration is only stored when first updated
        if ( factoryPid == null )
        {
            Dictionary props = new Hashtable();
            setAutoProperties( props, true );
            props.put( CONFIGURATION_NEW, Boolean.TRUE );
            persistenceManager.store( pid, props );
        }
    }


    public void delete() throws IOException
    {
        this.isDeleted = true;
        getPersistenceManager().delete( this.getPid() );
        getConfigurationManager().setDynamicBundleLocation( this.getPid(), null );
        getConfigurationManager().deleted( this );
    }


    public String getPid()
    {
        return getBaseId();
    }


    public String getFactoryPid()
    {
        return factoryPID;
    }




    /**
     * Returns the "official" bundle location as visible from the outside
     * world of code calling into the Configuration.getBundleLocation() method.
     * <p>
     * In other words: The {@link #getStaticBundleLocation()} is returned if
     * not <code>null</code>. Otherwise the {@link #getDynamicBundleLocation()}
     * is returned (which may also be <code>null</code>).
     */
    String getBundleLocation()
    {
        if ( staticBundleLocation != null )
        {
            return staticBundleLocation;
        }

        return dynamicBundleLocation;
    }


    String getDynamicBundleLocation()
    {
        return dynamicBundleLocation;
    }


    String getStaticBundleLocation()
    {
        return staticBundleLocation;
    }


    void setStaticBundleLocation( final String bundleLocation )
    {
        // CM 1.4; needed for bundle location change at the end
        final String oldBundleLocation = getBundleLocation();

        // 104.15.2.8 The bundle location will be set persistently
        this.staticBundleLocation = bundleLocation;
        storeSilently();

        // check whether the dynamic bundle location is different from the
        // static now. If so, the dynamic bundle location has to be
        // removed.
        if ( bundleLocation != null && getDynamicBundleLocation() != null
            && !bundleLocation.equals( getDynamicBundleLocation() ) )
        {
            setDynamicBundleLocation( null, false );
        }

        // CM 1.4
        this.getConfigurationManager().locationChanged( this, oldBundleLocation );
    }


    void setDynamicBundleLocation( final String bundleLocation, final boolean dispatchConfiguration )
    {
        // CM 1.4; needed for bundle location change at the end
        final String oldBundleLocation = getBundleLocation();

        this.dynamicBundleLocation = bundleLocation;
        this.getConfigurationManager().setDynamicBundleLocation( this.getBaseId(), bundleLocation );

        // CM 1.4
        if ( dispatchConfiguration )
        {
            this.getConfigurationManager().locationChanged( this, oldBundleLocation );

        }
    }


    /**
     * Dynamically binds this configuration to the given location unless
     * the configuration is already bound (statically or dynamically). In
     * the case of this configuration to be dynamically bound a
     * <code>CM_LOCATION_CHANGED</code> event is dispatched.
     */
    boolean tryBindLocation( final String bundleLocation )
    {
        if ( this.getBundleLocation() == null )
        {
            setDynamicBundleLocation( bundleLocation, true );
        }

        return true;
    }


    /**
     * Returns an optionally deep copy of the properties of this configuration
     * instance.
     * <p>
     * This method returns a copy of the internal dictionary. If the
     * <code>deepCopy</code> parameter is true array and collection values are
     * copied into new arrays or collections. Otherwise just a new dictionary
     * referring to the same objects is returned.
     *
     * @param deepCopy
     *            <code>true</code> if a deep copy is to be returned.
     * @return
     */
    public Dictionary getProperties( boolean deepCopy )
    {
        // no properties yet
        if ( properties == null )
        {
            return null;
        }

        CaseInsensitiveDictionary props = new CaseInsensitiveDictionary( properties, deepCopy );

        // fix special properties (pid, factory PID, bundle location)
        setAutoProperties( props, false );

        return props;
    }


    /* (non-Javadoc)
     * @see org.osgi.service.cm.Configuration#update()
     */
    public void update() throws IOException
    {
        PersistenceManager localPersistenceManager = getPersistenceManager();
        if ( localPersistenceManager != null )
        {
            // read configuration from persistence (again)
            if ( localPersistenceManager.exists( getPid() ) )
            {
                Dictionary properties = localPersistenceManager.load( getPid() );

                // ensure serviceReference pid
                String servicePid = ( String ) properties.get( Constants.SERVICE_PID );
                if ( servicePid != null && !getPid().equals( servicePid ) )
                {
                    throw new IOException( "PID of configuration file does match requested PID; expected " + getPid()
                        + ", got " + servicePid );
                }

                configureFromPersistence( properties );
            }

            // update the service but do not fire an CM_UPDATED event
            getConfigurationManager().updated( this, false );
        }
    }


    /* (non-Javadoc)
     * @see org.osgi.service.cm.Configuration#update(java.util.Dictionary)
     */
    public void update( Dictionary properties ) throws IOException
    {
        PersistenceManager localPersistenceManager = getPersistenceManager();
        if ( localPersistenceManager != null )
        {
            CaseInsensitiveDictionary newProperties = new CaseInsensitiveDictionary( properties );

            getConfigurationManager().log( LogService.LOG_DEBUG, "Updating config {0} with {1}", new Object[]
                { getPid(), newProperties } );

            setAutoProperties( newProperties, true );

            // persist new configuration
            localPersistenceManager.store( getPid(), newProperties );

            // if this is a factory configuration, update the factory with
            String factoryPid = getFactoryPid();
            if ( factoryPid != null )
            {
                // If this is a new factory configuration, we also have to add
                // it to the configuration manager cache
                if ( isNew() )
                {
                    getConfigurationManager().cacheConfiguration( this );
                }

                Factory factory = getConfigurationManager().getFactory( factoryPid );
                if ( factory.addPID( getPid() ) )
                {
                    // only write back if the pid was not already registered
                    // with the factory
                    try
                    {
                        factory.store();
                    }
                    catch ( IOException ioe )
                    {
                        getConfigurationManager().log( LogService.LOG_ERROR,
                            "Failure storing factory {0} with new configuration {1}", new Object[]
                                { factoryPid, getPid(), ioe } );
                    }
                }
            }

            // finally assign the configuration for use
            configure( newProperties );

            // update the service and fire an CM_UPDATED event
            getConfigurationManager().updated( this, true );
        }
    }


    //---------- Object overwrites --------------------------------------------

    public boolean equals( Object obj )
    {
        if ( obj == this )
        {
            return true;
        }

        if ( obj instanceof Configuration )
        {
            return getPid().equals( ( ( Configuration ) obj ).getPid() );
        }

        return false;
    }


    public int hashCode()
    {
        return getPid().hashCode();
    }


    public String toString()
    {
        return "Configuration PID=" + getPid() + ", factoryPID=" + factoryPID + ", bundleLocation=" + getBundleLocation();
    }


    // ---------- private helper -----------------------------------------------

    void store() throws IOException
    {
        // we don't need a deep copy, since we are not modifying
        // any value in the dictionary itself. we are just adding
        // properties to it, which are required for storing
        Dictionary props = getProperties( false );

        // if this is a new configuration, we just use an empty Dictionary
        if ( props == null )
        {
            props = new Hashtable();

            // add automatic properties including the bundle location (if
            // statically bound)
            setAutoProperties( props, true );
        }
        else
        {
            replaceProperty( props, ConfigurationAdmin.SERVICE_BUNDLELOCATION, getStaticBundleLocation() );
        }

        // only store now, if this is not a new configuration
        getPersistenceManager().store( getPid(), props );
    }


    /**
     * Returns the revision of this configuration object.
     * <p>
     * When getting both the configuration properties and this revision
     * counter, the two calls should be synchronized on this instance to
     * ensure configuration values and revision counter match.
     */
    long getRevision()
    {
        return revision;
    }


    /**
     * Returns <code>false</code> if this configuration contains configuration
     * properties. Otherwise <code>true</code> is returned and this is a
     * newly creted configuration object whose {@link #update(Dictionary)}
     * method has never been called.
     */
    boolean isNew()
    {
        return properties == null;
    }


    /**
     * Returns <code>true</code> if this configuration has already been deleted
     * on the persistence.
     */
    boolean isDeleted()
    {
        return isDeleted;
    }


    private void configureFromPersistence( Dictionary properties )
    {
        // if the this is not an empty/new configuration, accept the properties
        // otherwise just set the properties field to null
        if ( properties.get( CONFIGURATION_NEW ) == null )
        {
            configure( properties );
        }
        else
        {
            configure( null );
        }
    }

    private void configure( final Dictionary properties )
    {
        final CaseInsensitiveDictionary newProperties;
        if ( properties == null )
        {
            newProperties = null;
        }
        else
        {
            // remove predefined properties
            clearAutoProperties( properties );

            // ensure CaseInsensitiveDictionary
            if ( properties instanceof CaseInsensitiveDictionary )
            {
                newProperties = ( CaseInsensitiveDictionary ) properties;
            }
            else
            {
                newProperties = new CaseInsensitiveDictionary( properties );
            }
        }

        synchronized ( this )
        {
            this.properties = newProperties;
            this.revision++;
        }
    }


    void setAutoProperties( Dictionary properties, boolean withBundleLocation )
    {
        // set pid and factory pid in the properties
        replaceProperty( properties, Constants.SERVICE_PID, getPid() );
        replaceProperty( properties, ConfigurationAdmin.SERVICE_FACTORYPID, factoryPID );

        // bundle location is not set here
        if ( withBundleLocation )
        {
            replaceProperty( properties, ConfigurationAdmin.SERVICE_BUNDLELOCATION, getStaticBundleLocation() );
        }
        else
        {
            properties.remove( ConfigurationAdmin.SERVICE_BUNDLELOCATION );
        }
    }


    static void clearAutoProperties( Dictionary properties )
    {
        properties.remove( Constants.SERVICE_PID );
        properties.remove( ConfigurationAdmin.SERVICE_FACTORYPID );
        properties.remove( ConfigurationAdmin.SERVICE_BUNDLELOCATION );
    }
}
