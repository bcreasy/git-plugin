/*
 * The MIT License
 *
 * Copyright (c) 2004-2011, Oracle Corporation, Andrew Bayer, Anton Kozak, Nikita Levyankov
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package hudson.plugins.git;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Launcher.LocalLauncher;
import hudson.model.TaskListener;
import hudson.plugins.git.util.GitConstants;
import hudson.remoting.VirtualChannel;
import hudson.util.ArgumentListBuilder;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ListBranchCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.NoFilepatternException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryCache;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.util.FS;

public class GitAPI implements IGitAPI {
    private static final Logger LOGGER = Logger.getLogger(GitAPI.class.getName());
    private Launcher launcher;
    private FilePath workspace;
    private TaskListener listener;
    private String gitExe;
    private EnvVars environment;
    private Git jGitDelegate;
    private PersonIdent author;
    private PersonIdent committer;

    public GitAPI(String gitExe, FilePath workspace, TaskListener listener, EnvVars environment) {

        this.workspace = workspace;
        this.listener = listener;
        this.gitExe = gitExe;
        this.environment = environment;
        launcher = new LocalLauncher(GitSCM.VERBOSE ? listener : TaskListener.NULL);

        if (hasGitRepo()) {//Wrap file repository if exists in order to perform operations and initialize jGitDelegate
            try {
                File gitDir = RepositoryCache.FileKey.resolve(new File(workspace.getRemote()), FS.DETECTED);
                jGitDelegate = Git.wrap(new FileRepositoryBuilder().setGitDir(gitDir).build());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Returns author
     *
     * @return {@link PersonIdent}
     */
    protected PersonIdent getAuthor() {
        return author;
    }

    /**
     * Returns committer
     *
     * @return {@link PersonIdent}
     */
    protected PersonIdent getCommitter() {
        return committer;
    }

    public String getGitExe() {
        return gitExe;
    }

    public EnvVars getEnvironment() {
        return environment;
    }

    public void init() throws GitException {
        if (hasGitRepo()) {
            throw new GitException(Messages.GitAPI_Repository_FailedInitTwiceMsg());
        }
        jGitDelegate = Git.init().setDirectory(new File(workspace.getRemote())).call();
    }

    public boolean hasGitRepo() throws GitException {
        return hasGitRepo(Constants.DOT_GIT);
    }

    public boolean hasGitRepo(String gitDir) throws GitException {
        try {
            FilePath dotGit = workspace.child(gitDir);
            return dotGit.exists();
        } catch (SecurityException ex) {
            throw new GitException(Messages.GitAPI_Repository_SecurityFailureCheckMsg(), ex);
        } catch (Exception e) {
            throw new GitException(Messages.GitAPI_Repository_FailedCheckMsg(), e);
        }
    }

    public boolean hasGitModules() throws GitException {
        try {
            FilePath dotGit = workspace.child(".gitmodules");
            return dotGit.exists();
        } catch (SecurityException ex) {
            throw new GitException(
                "Security error when trying to check for .gitmodules. Are you sure you have correct permissions?", ex);
        } catch (Exception e) {
            throw new GitException("Couldn't check for .gitmodules", e);
        }
    }

    public List<IndexEntry> getSubmodules(String treeIsh) throws GitException {
        List<IndexEntry> submodules = lsTree(treeIsh);

        // Remove anything that isn't a submodule
        for (Iterator<IndexEntry> it = submodules.iterator(); it.hasNext(); ) {
            if (!it.next().getMode().equals("160000")) {
                it.remove();
            }
        }
        return submodules;
    }

    public boolean hasGitModules(String treeIsh) throws GitException {
        return hasGitModules() && !getSubmodules(treeIsh).isEmpty();
    }

    public void fetch(String repository, String refspec) throws GitException {
        listener.getLogger().println(
            "Fetching upstream changes"
                + (repository != null ? " from " + repository : ""));

        ArgumentListBuilder args = new ArgumentListBuilder();
        args.add("fetch", "-t");

        if (repository != null) {
            args.add(repository);
            if (refspec != null) {
                args.add(refspec);
            }
        }

        launchCommand(args);
    }

    public void reset(boolean hard) throws GitException {
        listener.getLogger().println("Resetting workspace (git reset --hard)");

        ArgumentListBuilder args = new ArgumentListBuilder();
        args.add("reset");
        if (hard) {
            args.add("--hard");
        }

        launchCommand(args);
    }

    public void reset() throws GitException {
        reset(false);
    }

    public void fetch() throws GitException {
        fetch(null, null);
    }

    /**
     * Start from scratch and clone the whole repository. Cloning into an
     * existing directory is not allowed, so the workspace is first deleted
     * entirely, then <tt>git clone</tt> is performed.
     *
     * @param remoteConfig remote config
     * @throws GitException if deleting or cloning the workspace fails
     */
    public void clone(final RemoteConfig remoteConfig) throws GitException {
        listener.getLogger().println(Messages.GitAPI_Repository_CloningRepositoryMsg(remoteConfig.getName()));
        try {
            workspace.deleteRecursive();
        } catch (Exception e) {
            e.printStackTrace(listener.error(Messages.GitAPI_Workspace_FailedCleanupMsg()));
            throw new GitException(Messages.GitAPI_Workspace_FailedDeleteMsg(), e);
        }

        // Assume only 1 URL for this repository
        final URIish source = remoteConfig.getURIs().get(0);

        try {
            workspace.act(new FilePath.FileCallable<String>() {

                private static final long serialVersionUID = 1L;

                public String invoke(File workspace,
                                     VirtualChannel channel) throws IOException {
                    jGitDelegate = Git.cloneRepository()
                        .setDirectory(workspace.getAbsoluteFile())
                        .setURI(source.toPrivateString())
                        .setRemote(remoteConfig.getName())
                        .call();
                    return Messages.GitAPI_Repository_CloneSuccessMsg(source.toPrivateString(),
                        workspace.getAbsolutePath());
                }
            });
        } catch (Exception e) {
            throw new GitException(Messages.GitAPI_Repository_FailedCloneMsg(source), e);
        }
    }

    public void clean() throws GitException {
        verifyGitRepository();

        reset(true); // reset to a clean HEAD first

        listener.getLogger().println("Cleaning workspace (git clean -dfx)");

        ArgumentListBuilder args = new ArgumentListBuilder();
        args.add("clean");
        args.add("-dfx");

        launchCommand(args); // fire off the git clean -dfx command

        //jGitDelegate.clean().call(); // this jgit call is fucking bullshit and doesn't work in all cases.
                                       // better off just calling the command manually.
    }

    public ObjectId revParse(String revName) throws GitException {
        String result = launchCommand("rev-parse", revName);
        return ObjectId.fromString(firstLine(result).trim());
    }

    public String describe(String commitIsh) throws GitException {
        String result = launchCommand("describe", "--tags", commitIsh);
        return firstLine(result).trim();
    }

    public void prune(RemoteConfig repository) throws GitException {
        ArgumentListBuilder args = new ArgumentListBuilder();
        args.add("remote", "prune", repository.getName());

        launchCommand(args);
    }

    private String firstLine(String result) {
        BufferedReader reader = new BufferedReader(new StringReader(result));
        String line;
        try {
            line = reader.readLine();
            if (line == null) {
                return null;
            }
            if (reader.readLine() != null) {
                throw new GitException("Result has multiple lines");
            }
        } catch (IOException e) {
            throw new GitException("Error parsing result", e);
        }

        return line;
    }

    public void changelog(String revFrom, String revTo, OutputStream outputStream) throws GitException {
        whatchanged(revFrom, revTo, outputStream, "--no-abbrev", "-M", "--pretty=raw");
    }

    private void whatchanged(String revFrom, String revTo, OutputStream outputStream, String... extraargs)
        throws GitException {
        String revSpec = revFrom + ".." + revTo;

        ArgumentListBuilder args = new ArgumentListBuilder();
        args.add(getGitExe(), "whatchanged");
        args.add(extraargs);
        args.add(revSpec);

        try {
            if (launcher.launch().cmds(args).envs(environment).stdout(
                outputStream).pwd(workspace).join() != 0) {
                throw new GitException("Error launching git whatchanged");
            }
        } catch (Exception e) {
            throw new GitException("Error performing git whatchanged", e);
        }
    }

    /**
     * Given a Revision, show it as if it were an entry from git whatchanged, so that it
     * can be parsed by GitChangeLogParser.
     *
     * @param r The Revision object
     * @return The git show output, in List form.
     * @throws GitException if errors were encountered running git show.
     */
    public List<String> showRevision(Revision r) throws GitException {
        String revName = r.getSha1String();
        String result = "";

        if (revName != null) {
            result = launchCommand("show", "--no-abbrev", "--format=raw", "-M", "--raw", revName);
        }

        List<String> revShow = new ArrayList<String>();

        if (result != null) {
            revShow = new ArrayList<String>(Arrays.asList(result.split("\\n")));
        }

        return revShow;
    }

    /**
     * Merge any changes into the head.
     *
     * @param revSpec the revision
     * @throws GitException if the emrge fails
     */
    public void merge(String revSpec) throws GitException {
        try {
            launchCommand("merge", revSpec);
        } catch (GitException e) {
            throw new GitException("Could not merge " + revSpec, e);
        }
    }

    /**
     * Init submodules.
     *
     * @throws GitException if executing the Git command fails
     */
    public void submoduleInit() throws GitException {
        launchCommand("submodule", "init");
    }

    /**
     * Sync submodule URLs
     */
    public void submoduleSync() throws GitException {
        // Check if git submodule has sync support.
        // Only available in git 1.6.1 and above
        launchCommand("submodule", "sync");
    }


    /**
     * Update submodules.
     *
     * @param recursive if true, will recursively update submodules (requires git>=1.6.5)
     * @throws GitException if executing the Git command fails
     */
    public void submoduleUpdate(boolean recursive) throws GitException {
        ArgumentListBuilder args = new ArgumentListBuilder();
        args.add("submodule", "update");
        if (recursive) {
            args.add("--init", "--recursive");
        }

        launchCommand(args);
    }

    /**
     * Cleans submodules
     *
     * @param recursive if true, will recursively clean submodules (requres git>=1.6.5)
     * @throws GitException if executing the git command fails
     */
    public void submoduleClean(boolean recursive) throws GitException {
        ArgumentListBuilder args = new ArgumentListBuilder();
        args.add("submodule", "foreach");
        if (recursive) {
            args.add("--recursive");
        }
        args.add("git clean -fdx");

        launchCommand(args);
    }

    /**
     * Get submodule URL
     *
     * @param name The name of the submodule
     * @throws GitException if executing the git command fails
     */
    public String getSubmoduleUrl(String name) throws GitException {
        String result = launchCommand("config", "--get", "submodule." + name + ".url");
        return firstLine(result).trim();
    }

    /**
     * Set submodule URL
     *
     * @param name The name of the submodule
     * @param url The new value of the submodule's URL
     * @throws GitException if executing the git command fails
     */
    public void setSubmoduleUrl(String name, String url) throws GitException {
        launchCommand("config", "submodule." + name + ".url", url);
    }

    /**
     * Get a remote's URL
     *
     * @param name The name of the remote (e.g. origin)
     * @throws GitException if executing the git command fails
     */
    public String getRemoteUrl(String name) throws GitException {
        String result = launchCommand("config", "--get", "remote." + name + ".url");
        return firstLine(result).trim();
    }

    /**
     * Set a remote's URL
     *
     * @param name The name of the remote (e.g. origin)
     * @param url The new value of the remote's URL
     * @throws GitException if executing the git command fails
     */
    public void setRemoteUrl(String name, String url) throws GitException {
        launchCommand("config", "remote." + name + ".url", url);
    }

    /**
     * From a given repository, get a remote's URL
     *
     * @param name The name of the remote (e.g. origin)
     * @param GIT_DIR The path to the repository (must be to .git dir)
     * @throws GitException if executing the git command fails
     */
    public String getRemoteUrl(String name, String GIT_DIR) throws GitException {
        String result
            = launchCommand("--git-dir=" + GIT_DIR,
            "config", "--get", "remote." + name + ".url");
        return firstLine(result).trim();
    }

    /**
     * For a given repository, set a remote's URL
     *
     * @param name The name of the remote (e.g. origin)
     * @param url The new value of the remote's URL
     * @param GIT_DIR The path to the repository (must be to .git dir)
     * @throws GitException if executing the git command fails
     */
    public void setRemoteUrl(String name, String url, String GIT_DIR) throws GitException {
        launchCommand("--git-dir=" + GIT_DIR,
            "config", "remote." + name + ".url", url);
    }

    /**
     * Get the default remote.
     *
     * @param _default_ The default remote to use if more than one exists.
     * @return _default_ if it exists, otherwise return the first remote.
     * @throws GitException if executing the git command fails
     */
    public String getDefaultRemote(String _default_) throws GitException {
        BufferedReader rdr =
            new BufferedReader(
                new StringReader(launchCommand("remote"))
            );

        List<String> remotes = new ArrayList<String>();

        String line;
        try {
            while ((line = rdr.readLine()) != null) {
                remotes.add(line);
            }
        } catch (IOException e) {
            throw new GitException("Error parsing remotes", e);
        }

        if (remotes.contains(_default_)) {
            return _default_;
        } else if (remotes.size() >= 1) {
            return remotes.get(0);
        } else {
            throw new GitException("No remotes found!");
        }
    }

    /**
     * Get the default remote.
     *
     * @return "origin" if it exists, otherwise return the first remote.
     * @throws GitException if executing the git command fails
     */
    public String getDefaultRemote() throws GitException {
        return getDefaultRemote(Constants.DEFAULT_REMOTE_NAME);
    }

    /**
     * Detect whether a repository is bare or not.
     *
     * @throws GitException
     */
    public boolean isBareRepository() throws GitException {
        return isBareRepository("");
    }

    /**
     * Detect whether a repository at the given path is bare or not.
     *
     * @param GIT_DIR The path to the repository (must be to .git dir).
     * @throws GitException
     */
    public boolean isBareRepository(String GIT_DIR) throws GitException {
        String ret;
        if ("".equals(GIT_DIR)) {
            ret = launchCommand("rev-parse", "--is-bare-repository");
        } else {
            String gitDir = "--git-dir=" + GIT_DIR;
            ret = launchCommand(gitDir, "rev-parse", "--is-bare-repository");
        }

        if ("false".equals(firstLine(ret).trim())) {
            return false;
        } else {
            return true;
        }
    }

    private String pathJoin(String a, String b) {
        return new File(a, b).toString();
    }

    /**
     * Fixes urls for submodule as stored in .git/config and
     * $SUBMODULE/.git/config for when the remote repo is NOT a bare repository.
     * It is only really possible to detect whether a repository is bare if we
     * have local access to the repository.  If the repository is remote, we
     * therefore must default to believing that it is either bare or NON-bare.
     * The defaults are according to the ending of the super-project
     * remote.origin.url:
     * - Ends with "/.git":  default is NON-bare
     * -         otherwise:  default is bare
     * .
     *
     * @param listener The task listener.
     * @throws GitException if executing the git command fails
     */
    public void fixSubmoduleUrls(String remote,
                                 TaskListener listener) throws GitException {
        boolean is_bare = true;

        URI origin;
        try {
            String url = getRemoteUrl(remote);

            // ensure that any /.git ending is removed
            String gitEnd = pathJoin("", ".git");
            if (url.endsWith(gitEnd)) {
                url = url.substring(0, url.length() - gitEnd.length());
                // change the default detection value to NON-bare
                is_bare = false;
            }

            origin = new URI(url);
        } catch (URISyntaxException e) {
            // Sometimes the URI is of a form that we can't parse; like
            //   user@git.somehost.com:repository
            // In these cases, origin is null and it's best to just exit early.
            return;
        } catch (Exception e) {
            throw new GitException("Could determine remote.origin.url", e);
        }

        if (origin.getScheme() == null ||
            ("file".equalsIgnoreCase(origin.getScheme()) &&
                (origin.getHost() == null || "".equals(origin.getHost()))
            )
            ) {
            // The uri is a local path, so we will test to see if it is a bare
            // repository...
            List<String> paths = new ArrayList<String>();
            paths.add(origin.getPath());
            paths.add(pathJoin(origin.getPath(), ".git"));

            for (String path : paths) {
                try {
                    is_bare = isBareRepository(path);
                    break;// we can break already if we don't have an exception
                } catch (GitException e) {
                    LOGGER.log(Level.FINEST, "Exception occurred while detecting repository type by path: " + path);
                }
            }
        }

        if (!is_bare) {
            try {
                List<IndexEntry> submodules = getSubmodules("HEAD");

                for (IndexEntry submodule : submodules) {
                    // First fix the URL to the submodule inside the super-project
                    String sUrl = pathJoin(origin.getPath(), submodule.getFile());
                    setSubmoduleUrl(submodule.getFile(), sUrl);

                    // Second, if the submodule already has been cloned, fix its own
                    // url...
                    String subGitDir = pathJoin(submodule.getFile(), ".git");

                    /* it is possible that the submodule does not exist yet
                     * since we wait until after checkout to do 'submodule
                     * udpate' */
                    if (hasGitRepo(subGitDir) && !"".equals(getRemoteUrl("origin", subGitDir))) {
                        setRemoteUrl("origin", sUrl, subGitDir);
                    }
                }
            } catch (GitException e) {
                // this can fail for example HEAD doesn't exist yet
                LOGGER.log(Level.FINEST, "Exception occurred while working with git repo.");
            }
        } else {
            LOGGER.log(Level.FINER, "The origin is non-bare.");
            // we've made a reasonable attempt to detect whether the origin is
            // non-bare, so we'll just assume it is bare from here on out and
            // thus the URLs are correct as given by (which is default behavior)
            //    git config --get submodule.NAME.url
        }
    }

    /**
     * Set up submodule URLs so that they correspond to the remote pertaining to
     * the revision that has been checked out.
     */
    public void setupSubmoduleUrls(Revision rev, TaskListener listener) throws GitException {
        String remote;

        Iterator<Branch> bi = rev.getBranches().iterator();
        if (bi.hasNext()) {
            // this is supposed to be a remote branch
            String b = bi.next().getName();
            if (b != null) {
                int slash = b.indexOf('/');

                if (slash == -1) {
                    throw new GitException("no remote from branch name (" + b + ")");
                }

                remote = getDefaultRemote(b.substring(0, slash));
            } else {
                remote = getDefaultRemote();
            }
        } else {
            remote = getDefaultRemote();
        }

        setupSubmoduleUrls(remote, listener);
    }

    public void setupSubmoduleUrls(String remote, TaskListener listener) throws GitException {
        // This is to make sure that we don't miss any new submodules or
        // changes in submodule origin paths...
        submoduleInit();
        submoduleSync();
        // This allows us to seamlessly use bare and non-bare superproject
        // repositories.
        fixSubmoduleUrls(remote, listener);
    }

    public void tag(String tagName, String comment) throws GitException {
        tagName = tagName.replace(' ', '_');
        try {
            launchCommand("tag", "-a", "-f", "-m", comment, tagName);
        } catch (GitException e) {
            throw new GitException("Could not apply tag " + tagName, e);
        }
    }

    /**
     * Launch command using the workspace as working directory
     *
     * @param args
     * @return command output
     * @throws GitException
     */
    public String launchCommand(ArgumentListBuilder args) throws GitException {
        return launchCommandIn(args, workspace);
    }

    /**
     * Launch command using the workspace as working directory
     *
     * @param args
     * @return command output
     * @throws GitException
     */
    public String launchCommand(String... args) throws GitException {
        return launchCommand(new ArgumentListBuilder(args));
    }

    /**
     * @param args
     * @param workDir
     * @return command output
     * @throws GitException
     */
    private String launchCommandIn(ArgumentListBuilder args, FilePath workDir) throws GitException {
        ByteArrayOutputStream fos = new ByteArrayOutputStream();

        try {
            args.prepend(getGitExe());
            int status = launcher.launch().cmds(args.toCommandArray()).
                envs(environment).stdout(fos).pwd(workDir).join();

            String result = fos.toString();

            if (status != 0) {
                throw new GitException(
                    "Command \"" + StringUtils.join(args.toCommandArray(), " ") + "\" returned status code " + status
                        + ": " + result);
            }

            return result;
        } catch (Exception e) {
            throw new GitException("Error performing command: " + StringUtils.join(args.toCommandArray(), " ")
                + "\n" + e.getMessage(), e);
        }
    }

    public void push(RemoteConfig repository, String refspec) throws GitException {
        ArgumentListBuilder args = new ArgumentListBuilder();
        args.add("push", repository.getURIs().get(0).toPrivateString());

        if (refspec != null) {
            args.add(refspec);
        }

        launchCommand(args);
        // Ignore output for now as there's many different formats
        // That are possible.
    }

    private List<Branch> parseBranches(String fos) throws GitException {
        // TODO: git branch -a -v --abbrev=0 would do this in one shot..

        List<Branch> tags = new ArrayList<Branch>();

        BufferedReader rdr = new BufferedReader(new StringReader(fos));
        String line;
        try {
            while ((line = rdr.readLine()) != null) {
                // Ignore the 1st
                line = line.substring(2);
                // Ignore '(no branch)' or anything with " -> ", since I think
                // that's just noise
                if ((!line.startsWith("("))
                    && (line.indexOf(" -> ") == -1)) {
                    tags.add(new Branch(line, revParse(line)));
                }
            }
        } catch (IOException e) {
            throw new GitException("Error parsing branches", e);
        }

        return tags;
    }

    public List<Branch> getBranches() throws GitException {
        verifyGitRepository();
        List<Ref> refList = jGitDelegate.branchList().setListMode(ListBranchCommand.ListMode.ALL).call();
        return parseRefList(refList);
    }

    public List<Branch> getRemoteBranches() throws GitException, IOException {
        verifyGitRepository();
        List<Ref> refList = jGitDelegate.branchList().setListMode(ListBranchCommand.ListMode.REMOTE).call();
        return parseRefList(refList);
    }

    public List<Branch> getBranchesContaining(String revspec)
        throws GitException {
        return parseBranches(launchCommand("branch", "-a", "--contains", revspec));
    }

    public void checkout(String commitish) throws GitException {
        checkoutBranch(null, commitish);
    }

    public void checkoutBranch(String branch, String commitish) throws GitException {
        verifyGitRepository();
        try {
            // First, checkout to detached HEAD, so we can delete the branch.
            launchCommand("checkout", "-f", commitish);

            if (null != branch) {
                jGitDelegate.checkout()
                    .setForce(true)
                    .setStartPoint(commitish)
                    .setName(branch)
                    .setCreateBranch(true)
                    .call();
            }
        } catch (GitAPIException e) {
            throw new GitException(Messages.GitAPI_Branch_CheckoutErrorMsg(branch, commitish), e);
        }
    }

    public boolean tagExists(String tagName) throws GitException {
        tagName = tagName.replace(' ', '_');
        return launchCommand("tag", "-l", tagName).trim().equals(tagName);
    }

    public void deleteBranch(String name) throws GitException {
        verifyGitRepository();
        try {
            jGitDelegate.branchDelete().setBranchNames(name).call();
        } catch (GitAPIException e) {
            throw new GitException(Messages.GitAPI_Branch_DeleteErrorMsg(name), e);
        }
    }

    public void deleteTag(String tagName) throws GitException {
        tagName = tagName.replace(' ', '_');
        try {
            launchCommand("tag", "-d", tagName);
        } catch (GitException e) {
            throw new GitException("Could not delete tag " + tagName, e);
        }
    }

    public List<IndexEntry> lsTree(String treeIsh) throws GitException {
        List<IndexEntry> entries = new ArrayList<IndexEntry>();
        String result = launchCommand("ls-tree", treeIsh);

        BufferedReader rdr = new BufferedReader(new StringReader(result));
        String line;
        try {
            while ((line = rdr.readLine()) != null) {
                String[] entry = line.split("\\s+");
                entries.add(new IndexEntry(entry[0], entry[1], entry[2],
                    entry[3]));
            }
        } catch (IOException e) {
            throw new GitException("Error parsing ls tree", e);
        }

        return entries;
    }

    public List<ObjectId> revListAll() throws GitException {
        return revList("--all");
    }

    public List<ObjectId> revListBranch(String branchId) throws GitException {
        return revList(branchId);
    }

    public List<ObjectId> revList(String... extraArgs) throws GitException {
        List<ObjectId> entries = new ArrayList<ObjectId>();
        ArgumentListBuilder args = new ArgumentListBuilder("rev-list");
        args.add(extraArgs);
        String result = launchCommand(args);
        BufferedReader rdr = new BufferedReader(new StringReader(result));
        String line;

        try {
            while ((line = rdr.readLine()) != null) {
                // Add the SHA1
                entries.add(ObjectId.fromString(line));
            }
        } catch (IOException e) {
            throw new GitException("Error parsing rev list", e);
        }

        return entries;
    }

    public boolean isCommitInRepo(String sha1) {
        RevWalk revWalk = new RevWalk(jGitDelegate.getRepository());
        try {
            revWalk.parseCommit(ObjectId.fromString(sha1));
        } catch (IOException e) {
            return false;
        }
        return true;
    }

    public void add(String filePattern) throws GitException {
        verifyGitRepository();
        try {
            jGitDelegate.add().addFilepattern(filePattern).call();
        } catch (NoFilepatternException ex) {
            throw new GitException(ex);
        }
    }

    public void branch(String name) throws GitException {
        verifyGitRepository();
        try {
            jGitDelegate.branchCreate().setName(name).call();
        } catch (GitAPIException e) {
            throw new GitException(Messages.GitAPI_Branch_CreateErrorMsg(name), e);
        }
    }

    public void commit(String message) throws GitException {
        verifyGitRepository();
        parseEnvVars(getEnvironment());
        try {
            jGitDelegate.commit()
                .setMessage(message)
                .setAuthor(getAuthor())
                .setCommitter(getCommitter())
                .call();
        } catch (Exception e) {
            throw new GitException(Messages.GitAPI_Commit_FailedMsg(message), e);
        }
    }

    public void commit(File f) throws GitException {
        try {
            launchCommand("commit", "-F", f.getAbsolutePath());
        } catch (GitException e) {
            throw new GitException("Cannot commit " + f, e);
        }
    }

    public void fetch(RemoteConfig remoteRepository) throws GitException {
        // Assume there is only 1 URL / refspec for simplicity
        fetch(remoteRepository.getURIs().get(0).toPrivateString(),
            remoteRepository.getFetchRefSpecs().get(0).toString());

    }

    public ObjectId mergeBase(ObjectId id1, ObjectId id2) {
        try {
            String result;
            try {
                result = launchCommand("merge-base", id1.name(), id2.name());
            } catch (GitException ge) {
                return null;
            }


            BufferedReader rdr = new BufferedReader(new StringReader(result));
            String line;

            while ((line = rdr.readLine()) != null) {
                // Add the SHA1
                return ObjectId.fromString(line);
            }
        } catch (Exception e) {
            throw new GitException("Error parsing merge base", e);
        }

        return null;
    }

    public String getAllLogEntries(String branch) {
        return launchCommand("log", "--all", "--pretty=format:'%H#%ct'", branch);
    }

    protected Repository getRepository() throws IOException {
        verifyGitRepository();
        return jGitDelegate.getRepository();
    }

    public List<Tag> getTagsOnCommit(String revName) throws GitException, IOException {
        Repository db = getRepository();
        ObjectId commit = db.resolve(revName);
        List<Tag> result = new ArrayList<Tag>();
        if (null != commit) {
            for (final Map.Entry<String, Ref> tag : db.getTags().entrySet()) {
                Ref ref = tag.getValue();
                if (ref.getObjectId().equals(commit)) {
                    result.add(new Tag(tag.getKey(), ref.getObjectId()));
                }
            }
        }
        return result;

    }

    public Set<String> getTagNames(String tagPattern) throws GitException {
        try {
            ArgumentListBuilder args = new ArgumentListBuilder();
            args.add("tag", "-l", tagPattern);

            String result = launchCommandIn(args, workspace);

            Set<String> tags = new HashSet<String>();
            BufferedReader rdr = new BufferedReader(new StringReader(result));
            String tag;
            while ((tag = rdr.readLine()) != null) {
                // Add the SHA1
                tags.add(tag);
            }
            return tags;
        } catch (Exception e) {
            throw new GitException("Error retrieving tag names", e);
        }
    }

    private void verifyGitRepository() {
        if (!hasGitRepo() || null == jGitDelegate) {
            throw new GitException(Messages.GitAPI_Repository_InvalidStateMsg());
        }
    }

    protected List<Branch> parseRefList(List<Ref> refList) {
        List<Branch> result = new ArrayList<Branch>();
        if (CollectionUtils.isNotEmpty(refList)) {
            for (Ref ref : refList) {
                Branch buildBranch = new Branch(ref);
                result.add(buildBranch);
                listener.getLogger().println(Messages.GitAPI_Branch_BranchInRepoMsg(buildBranch.getName()));
            }
        }
        return result;
    }

    /**
     * Check environment variables for authors and committers data. If name or email is not empty -
     * instantiate author/commiter as {@link PersonIdent}
     *
     * @param envVars environment variables.
     * @see hudson.plugins.git.util.GitConstants#GIT_AUTHOR_NAME_ENV_VAR
     * @see hudson.plugins.git.util.GitConstants#GIT_AUTHOR_EMAIL_ENV_VAR
     * @see hudson.plugins.git.util.GitConstants#GIT_COMMITTER_NAME_ENV_VAR
     * @see hudson.plugins.git.util.GitConstants#GIT_COMMITTER_EMAIL_ENV_VAR
     */
    protected void parseEnvVars(EnvVars envVars) {
        author = buildPersonIdent(envVars, GitConstants.GIT_AUTHOR_NAME_ENV_VAR, GitConstants.GIT_AUTHOR_EMAIL_ENV_VAR);
        committer = buildPersonIdent(envVars, GitConstants.GIT_COMMITTER_NAME_ENV_VAR,
            GitConstants.GIT_COMMITTER_EMAIL_ENV_VAR);
    }

    /**
     * Create person from environment vars by name key and email key.
     *
     * @param envVars EnvVars.
     * @param nameEnvKey String.
     * @param emailEnvKey String.
     * @return person instance
     */
    private PersonIdent buildPersonIdent(EnvVars envVars, String nameEnvKey, String emailEnvKey) {
        PersonIdent result = null;
        if (null != envVars) {
            String name = envVars.get(nameEnvKey);
            String email = envVars.get(emailEnvKey);
            if (StringUtils.isNotBlank(name) && StringUtils.isNotBlank(email)) {
                result = new PersonIdent(name, email);
            }
        }
        return result;
    }
}
