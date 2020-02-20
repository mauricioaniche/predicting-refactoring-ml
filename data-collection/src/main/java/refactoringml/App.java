package refactoringml;

import com.github.javaparser.utils.Log;
import com.google.common.io.Files;
import org.apache.commons.io.FileUtils;
import org.apache.log4j.Logger;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.refactoringminer.api.GitHistoryRefactoringMiner;
import org.refactoringminer.api.GitService;
import org.refactoringminer.api.Refactoring;
import org.refactoringminer.api.RefactoringHandler;
import org.refactoringminer.rm1.GitHistoryRefactoringMinerImpl;
import org.refactoringminer.util.GitServiceImpl;
import refactoringml.db.CommitMetaData;
import refactoringml.db.Database;
import refactoringml.db.Project;
import refactoringml.util.Counter;
import refactoringml.util.Counter.CounterResult;
import refactoringml.util.JGitUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import static refactoringml.util.FilePathUtils.enforceUnixPaths;
import static refactoringml.util.FilePathUtils.lastSlashDir;
import static refactoringml.util.JGitUtils.extractProjectNameFromGitUrl;

public class App {
	//config properties for the data-collection app at resources/config.property
	private static Properties configProperties;
	private static String configName = "config.properties";

	private String gitUrl;
	private String filesStoragePath;
	private Database db;
	private String lastCommitToProcess;
	private boolean storeFullSourceCode;

	private static final Logger log = Logger.getLogger(App.class);
	private String datasetName;
	private int exceptionsCount = 0;

	String commitIdToProcess;
	List<Refactoring> refactoringsToProcess;
	private int threshold;

	public App (String datasetName,
	            String gitUrl,
	            String filesStoragePath,
	            Database db, 
	            boolean storeFullSourceCode) {
		this(datasetName, gitUrl, filesStoragePath, db, null, storeFullSourceCode);
	}
	public App (String datasetName,
	            String gitUrl,
	            String filesStoragePath,
	            Database db,
	            String lastCommitToProcess,
	            boolean storeFullSourceCode) {

		this.datasetName = datasetName;
		this.gitUrl = gitUrl;
		this.filesStoragePath = enforceUnixPaths(filesStoragePath + extractProjectNameFromGitUrl(gitUrl)); // add project as subfolder
		this.db = db;
		this.lastCommitToProcess = lastCommitToProcess;
		this.storeFullSourceCode = storeFullSourceCode;
	}

	public Project run () throws Exception {
		// do not run if the project is already in the database
		if (db.projectExists(gitUrl)) {
			String message = String.format("Project %s already in the database", gitUrl);
			throw new IllegalArgumentException(message);
		}

		long start = System.currentTimeMillis();

		GitService gitService = new GitServiceImpl();
		GitHistoryRefactoringMiner miner = new GitHistoryRefactoringMinerImpl();

		// creates a temp dir to store the project
		String newTmpDir = lastSlashDir(Files.createTempDir().getAbsolutePath());
		String clonePath = (Project.isLocal(gitUrl) ? gitUrl : newTmpDir + "repo").trim();

		try {

			// creates the directory in the storage
			if(storeFullSourceCode) {
				new File(filesStoragePath).mkdirs();
			}

			log.info("Refactoring analyzer");
			log.info("Starting project " + gitUrl + "(clone at " + clonePath + ")");
			final Repository repo = gitService.cloneIfNotExists(clonePath, gitUrl);
			final Git git = Git.open(new File(lastSlashDir(clonePath) + ".git"));

			// identifies the main branch of that repo
			String mainBranch = discoverMainBranch(git);
			log.debug("main branch: " + mainBranch);

			String lastCommitHash = getHead(git);

			CounterResult counterResult = Counter.countProductionAndTestFiles(clonePath);
			long projectSize = FileUtils.sizeOfDirectory(new File(clonePath));
			int numberOfCommits = numberOfCommits(git);

			String rawCommitThresholds = App.getProperty("stableCommitThresholds");
			Project project = new Project(datasetName, gitUrl, extractProjectNameFromGitUrl(gitUrl), Calendar.getInstance(),
					numberOfCommits, rawCommitThresholds, lastCommitHash, counterResult, projectSize);
			log.debug("Set project stable commit threshold(s) to: " + project.getCommitCountThresholds());

			db.openSession();
			db.persist(project);
			db.commit();

			final ProcessMetricsCollector processMetrics = new ProcessMetricsCollector(project, db, repo, mainBranch,
					filesStoragePath, lastCommitToProcess, project.getCommitCountThresholds());
			final RefactoringAnalyzer refactoringAnalyzer = new RefactoringAnalyzer(project, db, repo, filesStoragePath, storeFullSourceCode);

			RefactoringHandler handler = getRefactoringHandler(git);

			// get all commits in the repo, and to each commit with a refactoring, extract the metrics
			RevWalk walk = JGitUtils.getReverseWalk(repo, mainBranch);
			RevCommit currentCommit = walk.next();

			int refactoringMinerTimeout = Integer.valueOf(getProperty("timeout"));
			log.debug("Set Refactoring Miner timeout to " + refactoringMinerTimeout + " seconds.");

			for (boolean endFound = false; currentCommit!=null && !endFound; currentCommit = walk.next()) {
				// we only analyze commits that have one parent
				// i.e., ignore merge commits
				if(currentCommit.getParentCount() > 1)
					continue;

				log.debug("Visiting commit " + currentCommit.getId().getName());

				// did we find the last commit to process?
				// if so, process it and then stop
				if (currentCommit.equals(lastCommitToProcess))
					endFound = true;

				String commitHash = currentCommit.getId().getName();

				log.debug("Invoking refactoring miner for commit " + commitHash);

				refactoringsToProcess = null;
				commitIdToProcess = null;

				// we define a timeout of 20 seconds for RefactoringMiner to find a refactoring.
				// Note that we only run it if the commit has a parent, i.e, skip the first commit of the repo
				if(currentCommit.getParentCount()==1)
					miner.detectAtCommit(repo, commitHash, handler, refactoringMinerTimeout);

				//stores all the ck metrics for the current commit
				Set<Long> allRefactoringCommits = new HashSet<Long>();

				// if timeout has happened, refactoringsToProcess and commitIdToProcess will be null
				boolean thereIsRefactoringToProcess = refactoringsToProcess != null && commitIdToProcess != null;
				if (thereIsRefactoringToProcess) {
					for (Refactoring ref : refactoringsToProcess) {
						try {
							db.openSession();
							CommitMetaData commitMetaData = new CommitMetaData(currentCommit, project);
							db.persist(commitMetaData);

							allRefactoringCommits.addAll(refactoringAnalyzer.collectCommitData(currentCommit, ref, commitMetaData));
							db.commit();
						} catch (Exception e) {
							exceptionsCount++;
							log.error("Error when collecting commit data", e);
							db.rollback();
						} finally {
							db.close();
						}
					}
				} else {
					// timeout happened, so count it as an exception
					log.error("Refactoring Miner returned null for " + commitHash + ". Possibly this is the first commit of the project, or a timeout.");
					exceptionsCount++;
				}

				//collect the process metrics for the current commit
				processMetrics.collectMetrics(currentCommit, allRefactoringCommits, thereIsRefactoringToProcess);
			}

			walk.close();

			long end = System.currentTimeMillis();
			log.info(String.format("Finished in %.2f minutes", ( ( end - start ) / 1000.0 / 60.0 )));

			// set finished data
			// note that if this process crashes, finished date will be equals to null in the database
			// these projects must be deleted manually afterwards....
			db.openSession();
			project.setFinishedDate(Calendar.getInstance());
			project.setExceptions(exceptionsCount);
			db.update(project);
			db.commit();

			return project;
		} finally {
			// delete the tmp dir that stores the project
			FileUtils.deleteDirectory(new File(newTmpDir));
		}
	}

	private String getHead(Git git) throws IOException {
		return git.getRepository().resolve(Constants.HEAD).getName();
	}

	private RefactoringHandler getRefactoringHandler(Git git) {
		return new RefactoringHandler() {
				@Override
				public void handle(String commitId, List<Refactoring> refactorings) {
					commitIdToProcess = commitId;
					refactoringsToProcess = refactorings;
				}

				@Override
				public void handleException(String commitId, Exception e) {
					exceptionsCount++;
					log.error("RefactoringMiner could not handle commit Id " + commitId, e);
					resetGitRepo();

				}

				private void resetGitRepo() {
					try {
						git.reset().setMode(ResetCommand.ResetType.HARD).call();
					} catch (GitAPIException e1) {
						log.error("Reset failed", e1);
					}
				}
			};
	}

	private int numberOfCommits(Git git) throws GitAPIException {
		Iterable<RevCommit> commits = git.log().call();
		int count = 0;
		for(RevCommit ignored : commits) {
			count++;
		}

		return count;
	}


	private String discoverMainBranch(Git git) throws IOException {
		return git.getRepository().getBranch();
	}

	private static Properties fetchProperties(){
		if(configProperties!= null)
			return configProperties;

		String propertiesPath = Thread.currentThread().getContextClassLoader().getResource(configName).getPath();
		configProperties = new Properties();
		try{
			configProperties.load(new FileInputStream(propertiesPath));
		} catch (Exception e) {
			log.error(e.getClass().getCanonicalName() + " while loading config file from: " + propertiesPath, e);
			throw new RuntimeException("Could not load config properties.");
		}
		return configProperties;
	}

	//query the config properties for the given config name
	public static String getProperty(String propertyName) {
		return fetchProperties().getProperty(propertyName);
	}

	//Only use this for tests
	@Deprecated
	public static Object setProperty(String propertyName, String propertyValue){
		return fetchProperties().setProperty(propertyName, propertyValue);
	}
}