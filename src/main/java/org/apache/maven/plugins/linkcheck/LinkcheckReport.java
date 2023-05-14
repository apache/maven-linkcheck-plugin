package org.apache.maven.plugins.linkcheck;

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

import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.doxia.linkcheck.HttpBean;
import org.apache.maven.doxia.linkcheck.LinkCheck;
import org.apache.maven.doxia.linkcheck.LinkCheckException;
import org.apache.maven.doxia.linkcheck.model.LinkcheckModel;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.reporting.AbstractMavenReport;
import org.apache.maven.reporting.MavenReportException;
import org.apache.maven.settings.Proxy;
import org.apache.maven.settings.Settings;
import org.codehaus.plexus.i18n.I18N;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.ReaderFactory;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Generates a <code>Linkcheck</code> report.
 *
 * @author <a href="mailto:vincent.siveton@gmail.com">Vincent Siveton</a>
 * @version $Id$
 * @since 1.0
 */
@Mojo( name = "linkcheck" )
public class LinkcheckReport
    extends AbstractMavenReport
{
    // ----------------------------------------------------------------------
    // Report Components
    // ----------------------------------------------------------------------

    /**
     * Internationalization.
     */
    @Component
    private I18N i18n;

    /**
     * LinkCheck component.
     */
    @Component
    private LinkCheck linkCheck;

    // ----------------------------------------------------------------------
    // Report Parameters
    // ----------------------------------------------------------------------

    /**
     * Local Repository.
     */
    @Parameter( defaultValue = "${localRepository}", required = true, readonly = true )
    private ArtifactRepository localRepository;

    /**
     * The Maven Settings.
     */
    @Parameter( defaultValue = "${settings}", readonly = true, required = true )
    private Settings settings;

    // ----------------------------------------------------------------------
    // Linkcheck parameters
    // ----------------------------------------------------------------------

    /**
     * Whether we are offline or not.
     */
    @Parameter( property = "linkcheck.offline", defaultValue = "${settings.offline}", required = true )
    private boolean offline;

    /**
     * If online, the HTTP method should automatically follow HTTP redirects, <tt>false</tt> otherwise.
     *
     * @parameter default-value="true"
     */
    @Parameter( defaultValue = "true" )
    private boolean httpFollowRedirect;

    /**
     * The location of the Linkcheck cache file.
     */
    @Parameter( defaultValue = "${project.build.directory}/linkcheck/linkcheck.cache", required = true )
    protected File linkcheckCache;

    /**
     * The location of the Linkcheck report file.
     */
    @Parameter( defaultValue = "${project.build.directory}/linkcheck/linkcheck.xml", required = true )
    protected File linkcheckOutput;

    // CHECKSTYLE_OFF: LineLength
    /**
     * The HTTP method to use. Currently supported are "GET" and "HEAD".
     * <dl>
     * <dt>HTTP GET</dt>
     * <dd>The HTTP GET method is defined in section 9.3 of <a href="http://www.ietf.org/rfc/rfc2616.txt">RFC2616</a>:
     * The GET method means retrieve whatever information (in the form of an entity) is identified by the Request-URI.</dd>
     * <dt>HTTP HEAD</dt>
     * <dd>The HTTP HEAD method is defined in section 9.4 of <a href="http://www.ietf.org/rfc/rfc2616.txt">RFC2616</a>:
     * The HEAD method is identical to GET except that the server MUST NOT return a message-body in the response.</dd>
     * </dl>
     */
    // CHECKSTYLE_ON: LineLength
    @Parameter( defaultValue = "head", required = true )
    private String httpMethod;

    /**
     * The list of HTTP errors to ignored, like <code>404</code>.
     *
     * @see {@link org.apache.commons.httpclient.HttpStatus} for all defined values.
     */
    @Parameter
    private Integer[] excludedHttpStatusErrors;

    /**
     * The list of HTTP warnings to ignored, like <code>301</code>.
     *
     * @see {@link org.apache.commons.httpclient.HttpStatus} for all defined values.
     */
    @Parameter
    private Integer[] excludedHttpStatusWarnings;

    /**
     * A list of pages to exclude. <br/>
     * <b>Note</b>: <br/>
     * <ul>
     * <li>This report, i.e. <code>linkcheck.html</code>, is always excluded.</li>
     * <li>May contain Ant-style wildcards and double wildcards, e.g. <code>apidocs/**</code>, etc.</li>
     * </ul>
     */
    @Parameter
    private String[] excludedPages;

    /**
     * The list of links to exclude. <br/>
     * <b>Note</b>: Patterns like <code>&#42;&#42;/dummy/&#42;</code> are allowed for excludedLink.
     */
    @Parameter
    private String[] excludedLinks;

    /**
     * The file encoding to use when Linkcheck reads the source files. If the property
     * <code>project.build.sourceEncoding</code> is not set, the platform default encoding is used.
     */
    @Parameter( property = "encoding", defaultValue = "${project.build.sourceEncoding}" )
    private String encoding;

    /**
     * The extra HttpClient parameters to be used when fetching links. For instance:
     * 
     * <pre>
     * &lt;httpClientParameters&gt;
     * &nbsp;&lt;property&gt;
     * &nbsp;&nbsp;&lt;name&gt;http.protocol.max-redirects&lt;/name&gt;
     * &nbsp;&nbsp;&lt;value&gt;10&lt;/value&gt;
     * &nbsp;&lt;/property&gt;
     * &lt;/httpClientParameters&gt;
     * </pre>
     * 
     * See <a href="http://hc.apache.org/httpclient-3.x/preference-api.html">HttpClient preference page</a>
     */
    @Parameter( property = "httpClientParameters" )
    private Properties httpClientParameters;

    /**
     * Set the timeout to be used when fetching links. A value of zero means the timeout is not used.
     */
    @Parameter( property = "timeout", defaultValue = "2000" )
    private int timeout;

    /**
     * <code>true</code> to skip the report execution, <code>false</code> otherwise.
     * The purpose is to prevent infinite call when {@link #forceSite} is enable.
     */
    @Parameter( property = "linkcheck.skip", defaultValue = "false" )
    private boolean skip;

    /**
     * <code>true</code> to force the site generation, <code>false</code> otherwise.
     * Using this parameter ensures that all documents have been correctly generated.
     */
    @Parameter( property = "linkcheck.forceSite", defaultValue = "true" )
    private boolean forceSite;

    /**
     * The base URL to use for absolute links (eg <code>/index.html</code>) in the site.
     */
    @Parameter( property = "linkcheck.baseURL", defaultValue = "${project.url}" )
    private String baseURL;

    // ----------------------------------------------------------------------
    // Instance fields
    // ----------------------------------------------------------------------

    /** Result of the linkcheck in {@link #execute()} */
    private LinkcheckModel result;

    protected static final String ICON_SUCCESS = "images/icon_success_sml.gif";

    protected static final String ICON_WARNING = "images/icon_warning_sml.gif";

    protected static final String ICON_INFO = "images/icon_info_sml.gif";

    protected static final String ICON_ERROR = "images/icon_error_sml.gif";

    private static final String PLUGIN_RESOURCES_BASE = "org/apache/maven/plugin/linkcheck";

    private static final String RESOURCE_NAMES[] = { ICON_SUCCESS, ICON_WARNING, ICON_INFO, ICON_ERROR };

    // ----------------------------------------------------------------------
    // Public methods
    // ----------------------------------------------------------------------

    /** {@inheritDoc} */
    public String getDescription( Locale locale )
    {
        return i18n.getString( "linkcheck-report", locale, "report.linkcheck.description" );
    }

    /** {@inheritDoc} */
    public String getName( Locale locale )
    {
        return i18n.getString( "linkcheck-report", locale, "report.linkcheck.name" );
    }

    /** {@inheritDoc} */
    public String getOutputName()
    {
        return "linkcheck";
    }

    /** {@inheritDoc} */
    public boolean canGenerateReport()
    {
        return !skip;
    }

    /** {@inheritDoc} */
    public void execute()
        throws MojoExecutionException
    {
        if ( !canGenerateReport() )
        {
            return;
        }

        checkEncoding();

        try
        {
            result = executeLinkCheck( getBasedir() );
        }
        catch ( LinkCheckException e )
        {
            throw new MojoExecutionException( "LinkCheckException: " + e.getMessage(), e );
        }
    }

    // ----------------------------------------------------------------------
    // Protected methods
    // ----------------------------------------------------------------------

    /** {@inheritDoc} */
    protected void executeReport( Locale locale )
        throws MavenReportException
    {
        if ( result == null )
        {
            getLog().debug( "Calling execute()" );

            try
            {
                this.execute();
            }
            catch ( MojoExecutionException e )
            {
                throw new MavenReportException( "MojoExecutionException: " + e.getMessage(), e );
            }
        }

        if ( result != null )
        {
            generateReport( locale, result );
            // free memory
            result = null;
        }
    }

    // ----------------------------------------------------------------------
    // Private methods
    // ----------------------------------------------------------------------

    private void checkEncoding()
    {
        if ( encoding == null || encoding.isEmpty() )
        {
            if ( getLog().isWarnEnabled() )
            {
                getLog().warn( "File encoding has not been set, using platform encoding " + ReaderFactory.FILE_ENCODING
                                   + ", i.e. build is platform dependent!" );
            }

            encoding = ReaderFactory.FILE_ENCODING;
        }
    }

    private File getBasedir()
        throws MojoExecutionException
    {
        File basedir;

        if ( forceSite )
        {
            basedir = new File( linkcheckOutput.getParentFile(), "tmpsite" );
            basedir.mkdirs();

            List<File> documents = null;
            try
            {
                documents = FileUtils.getFiles( basedir, "**/*.html", null );
            }
            catch ( IOException e )
            {
                getLog().error( "IOException: " + e.getMessage() );
                getLog().debug( e );
            }

            // if the site was not already generated, invoke it
            if ( documents == null || documents.size() == 0 )
            {
                getLog().info( "Invoking the maven-site-plugin to ensure that all files are generated..." );

                try
                {
                    SiteInvoker invoker = new SiteInvoker( localRepository, getLog() );
                    invoker.invokeSite( project, basedir );
                }
                catch ( IOException e )
                {
                    throw new MojoExecutionException( "IOException: " + e.getMessage(), e );
                }
            }
        }
        else
        {
            getLog().warn( "The number of documents analyzed by Linkcheck could differ from the actual "
                               + "number of documents!" );

            basedir = outputDirectory;
            basedir.mkdirs();
        }

        return basedir;
    }

    /**
     * Execute the <code>Linkcheck</code> tool.
     *
     * @param basedir not null
     * @throws LinkCheckException if any
     */
    private LinkcheckModel executeLinkCheck( File basedir )
        throws LinkCheckException
    {
        // Wrap linkcheck
        linkCheck.setOnline( !offline );
        linkCheck.setBasedir( basedir );
        linkCheck.setBaseURL( baseURL );
        linkCheck.setReportOutput( linkcheckOutput );
        linkCheck.setLinkCheckCache( linkcheckCache );
        linkCheck.setExcludedLinks( excludedLinks );
        linkCheck.setExcludedPages( getExcludedPages() );
        linkCheck.setExcludedHttpStatusErrors( asIntArray( excludedHttpStatusErrors ) );
        linkCheck.setExcludedHttpStatusWarnings( asIntArray( excludedHttpStatusWarnings ) );
        linkCheck.setEncoding( ( (encoding != null && !encoding.isEmpty()) ? encoding : ReaderFactory.UTF_8 ) );

        HttpBean bean = new HttpBean();
        bean.setMethod( httpMethod );
        bean.setFollowRedirects( httpFollowRedirect );
        bean.setTimeout( timeout );
        if ( httpClientParameters != null )
        {
            bean.setHttpClientParameters( httpClientParameters );
        }

        Proxy proxy = settings.getActiveProxy();
        if ( proxy != null )
        {
            bean.setProxyHost( proxy.getHost() );
            bean.setProxyPort( proxy.getPort() );
            bean.setProxyUser( proxy.getUsername() );
            bean.setProxyPassword( proxy.getPassword() );
        }
        linkCheck.setHttp( bean );

        return linkCheck.execute();
    }

    /**
     * @return the excludedPages defined by the user and also this report.
     */
    private String[] getExcludedPages()
    {
        String[] pagesToExclude = new String[excludedPages.length + 1];
        System.arraycopy( excludedPages, 0, pagesToExclude, 0, excludedPages.length );
        pagesToExclude[excludedPages.length] = getOutputName() + ".html";
        return pagesToExclude;
    }

    // ----------------------------------------------------------------------
    // Linkcheck report
    // ----------------------------------------------------------------------

    private void generateReport( Locale locale, LinkcheckModel linkcheckModel )
    {
        LinkcheckReportGenerator reportGenerator = new LinkcheckReportGenerator( i18n );

        reportGenerator.setExcludedHttpStatusErrors( excludedHttpStatusErrors );
        reportGenerator.setExcludedHttpStatusWarnings( excludedHttpStatusWarnings );
        reportGenerator.setExcludedLinks( excludedLinks );
        reportGenerator.setExcludedPages( excludedPages );
        reportGenerator.setHttpFollowRedirect( httpFollowRedirect );
        reportGenerator.setHttpMethod( httpMethod );
        reportGenerator.setOffline( offline );

        reportGenerator.generateReport( locale, linkcheckModel, getSink() );
        closeReport();

        // Copy the images
        copyStaticResources();
    }

    private void copyStaticResources()
    {
        try
        {
            getLog().debug( "Copying static linkcheck resources." );
            for ( String resourceName : RESOURCE_NAMES )
            {
                URL url = this.getClass().getClassLoader().getResource( PLUGIN_RESOURCES_BASE + "/" + resourceName );
                FileUtils.copyURLToFile( url, new File( getReportOutputDirectory(), resourceName ) );
            }
        }
        catch ( IOException e )
        {
            getLog().error( "Unable to copy icons for linkcheck report." );
            getLog().debug( e );
        }
    }

    private static int[] asIntArray( Integer[] array )
    {
        if ( array == null )
        {
            return null;
        }

        int[] newArray = new int[array.length];

        for ( int i = 0; i < array.length; i++ )
        {
            newArray[i] = array[i];
        }

        return newArray;
    }
}
