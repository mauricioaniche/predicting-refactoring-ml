package refactoringml;

import org.hibernate.SessionFactory;
import refactoringml.db.CommitMetaData;
import refactoringml.db.HibernateConfig;
import javax.persistence.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class PMTrackerDatabase {
	//Map class files onto their original process metrics.
	@ElementCollection
	@CollectionTable(name = "PMTrackerDatabase")
	@MapKeyColumn(name = "filePath")
	@Column(name = "pmTracker")
	private Map<String, ProcessMetricTracker> database;
	//All commit thresholds for this project for considering a class file as stable
	@Transient
	private List<Integer> commitThresholds;

	@Transient
	private final String databasePath =	"jdbc:mysql://localhost/pmDatabase";
	@Transient
	private final String databaseUsername = "root";
	@Transient
	private final String databasePassword = "root";
	@Transient
	private SessionFactory sf;

	public PMTrackerDatabase(List<Integer> commitThresholds) {
		this.sf = new HibernateConfig().getSessionFactory(databasePath, databaseUsername, databasePassword);
		sf.openSession();

		this.commitThresholds = commitThresholds;
		this.database = new HashMap<>();
	}

	//Empty the database and close the hibernate SessionFactory
	public void destroy(){
		//TODO: cleanse db
		sf.close();
	}

	//public interaction
	//Retrieve the process metrics tracker for the given fileName
	public ProcessMetricTracker find(String fileName) {
		return database.get(fileName);
	}

	//Find all stable instances in the database
	public List<ProcessMetricTracker> findStableInstances() {
		return database.values().stream()
				.filter(pmTracker -> pmTracker.calculateStability(commitThresholds))
				.collect(Collectors.toList());
	}

	/*
	Report the rename of a file in order to track its process metrics.
	In case of (various renames), the names are replaced, e.g.
	1. Rename People.java to Person.java: Person -> People_ProcessMetrics
	2. Rename Person.java to Human.java: Human -> People_ProcessMetrics
	 */
	public ProcessMetricTracker renameFile(String oldFileName, String newFileName, CommitMetaData commitMetaData){
		ProcessMetricTracker pmTracker = new ProcessMetricTracker(database.getOrDefault(oldFileName, new ProcessMetricTracker(newFileName, commitMetaData)));
		pmTracker.setFileName(newFileName);

		ProcessMetricTracker oldPMTracker = removeFile(oldFileName);

		database.put(newFileName, pmTracker);
		return oldPMTracker;
	}

	//Remove the given fileName from the process metrics database
	//Returns the old process metrics tracker of the deleted class file, if any existed in the database
	public ProcessMetricTracker removeFile(String fileName){
		return database.remove(fileName);
	}

	//Report a commit changing the given class file, the in memory database is updated accordingly
	public void reportChanges(String fileName, CommitMetaData commitMetaData, String authorName, int linesAdded, int linesDeleted) {
		ProcessMetricTracker pmTracker = database.getOrDefault(fileName, new ProcessMetricTracker(fileName, commitMetaData));
		pmTracker.reportCommit(commitMetaData.getCommitId(), commitMetaData.getCommitMessage(), authorName, linesAdded, linesDeleted);

		database.put(fileName, pmTracker);
	}

	//Reset the tracker with latest refactoring and its commit meta data
	//the commitCounter will be zero again
	public void reportRefactoring(String fileName, CommitMetaData commitMetaData) {
		ProcessMetricTracker pmTracker = database.getOrDefault(fileName, new ProcessMetricTracker(fileName, commitMetaData));
		pmTracker.resetCounter(commitMetaData);

		database.put(fileName, pmTracker);
	}

	public String toString(){
		return "PMDatabase{" +
				"database=" + database.toString() + ",\n" +
				"commitThreshold=" + commitThresholds.toString() +
				"}";
	}
}