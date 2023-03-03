package main.java;

import db.DBManager;
import file.FileIOManager;
import jcodelib.diffutil.TreeDiff;
import jcodelib.element.GTAction;
import jcodelib.jgit.ReposHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class CollectChanges {
	//Hard-coded projects - need to read it from DB.
	//public static String[] projects;
	public static String baseDir = "subjects";
	public static final String oldDir = "old";
	public static final String newDir = "new";
	public static long commitCount = 1;
	public static String [] projectArr = {"elasticsearch", "ballerina-lang", "crate", "neo4j","sakai", "wildfly"};

	//public final static Logger log = LoggerFactory.getLogger(CollectChanges.class);
	
	public static void main(String[] args) throws IOException {

		//Select SCD Tool here.
		//String tool = "ChangeDistiller";
		String tool = "GumTree";
		DBManager db = null;

		File file = new File("/exp/JCodeLib-log/GT-Alluxio.txt");

		if (!file.exists()) {
			file.createNewFile();
		}

		FileWriter fw = new FileWriter(file);
		PrintWriter writer = new PrintWriter(fw);
		
		try {
			//Change db.properties.
			db = new DBManager("src/main/resources/db.properties");

			//Connect DB.
			Connection con = db.getConnection();

			// if exception occurs, do rollback
			con.setAutoCommit(false);

			// Collect and store file_id, tool, change_type, entity_type, start_pos, script, run_time in DB.
			PreparedStatement psIns = con.prepareStatement("insert into changes_GT " +
					" ( file_id, tool, change_type, entity_type, start_pos, script ) " +
					" values ( ?, ?, ?, ?, ?, ? )");

			// Collect run time
			PreparedStatement psTime = con.prepareStatement("insert into changes_GT_runtime " +
					" (file_id, runtime_gitReset, runtime_editScript)" +
					" values(?, ?, ?)");

			// use command line arguments
			String project = args[0];

			System.out.println("Collecting Changes from " + project);
			String oldReposPath = String.join(File.separator, baseDir, oldDir, project) + File.separator;
			String newReposPath = String.join(File.separator, baseDir, newDir, project) + File.separator;
			File oldReposDir = new File(oldReposPath);
			File newReposDir = new File(newReposPath);

			// Prepare files.
			List<String> fileInfo = new ArrayList<>();

			PreparedStatement fileSel = con.prepareStatement("select c.commit_id commit_id, c.old_commit old_commit, c.new_commit new_commit, " +
					" f.file_path file_path, f.file_id file_id " +
					" from commits c, files f where c.commit_id = f.commit_id and c.project_name = '" + project + "'" +
					" order by file_id, commit_id ");
			ResultSet fileRS = fileSel.executeQuery();
			while (fileRS.next()) {
				String str = String.join(",",
				String.valueOf(fileRS.getInt("commit_id")),
				fileRS.getString("old_commit"),
				fileRS.getString("new_commit"),
				fileRS.getString("file_path"),
				String.valueOf(fileRS.getInt("file_id")));
				fileInfo.add(str);
			}

			fileRS.close();
			fileSel.close();

			System.out.println("Total " + fileInfo.size() + " revisions.");

			for (int i = 0; i < fileInfo.size(); i++) {

				String key = fileInfo.get(i);
				String[] tokens = key.split(",");
				String commitId = tokens[0];
				String oldCommitId = tokens[1];
				String newCommitId = tokens[2];
				String filePath = tokens[3];
				String fileId = tokens[4];
				System.out.println("CommitId : " + commitId + ", fileId : " + fileId + ", oldCommitId : " + oldCommitId + ", newCommitId : " + newCommitId);

				// Reset hard to old/new commit IDs.
				long gitResetStartTime = System.currentTimeMillis();
				ReposHandler.update(oldReposDir, oldCommitId);
				ReposHandler.update(newReposDir, newCommitId);
				long gitResetFinishTime = System.currentTimeMillis();
				long gitResetElapsedTime = gitResetFinishTime - gitResetStartTime;

				try {
					if (!filePath.contains("/test/")) // ignore test code in GitHub project
						continue;

					List<String> projectList = new ArrayList<>(Arrays.asList(projectArr));
					if(!filePath.contains("/org/") && projectList.contains(project))
						continue;

					File oldFile = new File(oldReposPath + filePath);
					File newFile = new File(newReposPath + filePath);
					String oldCode = FileIOManager.getContent(oldFile).intern();
					String newCode = FileIOManager.getContent(newFile).intern();
					if (oldCode.length() == 0 || newCode.length() == 0) {
						//Practically these files are deleted/inserted.
						//System.out.println("File " + fileId + "s are practically deleted or inserted.");
//						log.info("FileId : {} is practically deleted or inserted. And, commitId : {}, " +
//								"oldCommitId : {}, newCommitId: {}", fileId, commitId, oldCommitId, newCommitId);

						writer.println("1. FileId : " + fileId + ", commitId : " + commitId
								+ ", oldCommitId : " + oldCommitId + ", newCommitId : " + newCommitId
								+ " is practically deleted or inserted.");
						continue;
					}

					// Apply Source Code Differencing Tools.
					// GumTree
					List<GTAction> gumtreeChanges = TreeDiff.diffGumTreeWithGrouping(oldFile, newFile);

					for(GTAction c : gumtreeChanges) {
						psIns.clearParameters();
						psIns.setString(1, fileId); //file_id
						psIns.setString(2, tool); //tool: GT, etc..
						psIns.setString(3, c.actionType); // change_type
						psIns.setString(4, c.codeType); // entity_type
						psIns.setInt(5, c.startPos); // start_pos
						psIns.setString(6, c.toString()); // script
						psIns.addBatch();
					}

					for (GTAction c : gumtreeChanges) {
						psTime.clearParameters();
						psTime.setInt(1, Integer.parseInt(fileId)); //file_id
						psTime.setLong(2, gitResetElapsedTime);
						psTime.setLong(3, c.runTimeOfEditScript);
						psTime.addBatch();
					}

					if (commitCount % 100 == 0) {
						psIns.executeBatch();
						psIns.clearBatch();
						psTime.executeBatch();
						psTime.clearBatch();
						con.commit();
					}

					// Committing for the rest of the syntax that has not been committed
					psIns.executeBatch();
					psTime.executeBatch();
					con.commit();
					psIns.clearBatch();
					psTime.clearBatch();

					// ChangeDistiller
//						List<SourceCodeChange> changes = TreeDiff.diffChangeDistiller(oldFile, newFile);
//						int runTime = TreeDiff.runTime;
//						for(SourceCodeChange c : changes) {
//
//							psIns.clearParameters();
//							psIns.setString(1, fileId); //fileId
//							psIns.setString(2, tool); //tool
//							psIns.setString(3, c.getChangeType().toString()); //change_type
//							psIns.setString(4, c.getChangedEntity().toString()); //entity_type
//							psIns.setInt(5, 0); //start_pos
//							psIns.setString(6, c.toString()); //script
//							psIns.setInt(7, runTime); //run_time
//							psIns.addBatch();
//						}

				} catch (Exception e) {
//					log.error("In project {}, running error while processing fileId : {}, " +
//									"commitId : {}, oldCommitId : {}, newCommitId : {}",
//							project, fileId, commitId, oldCommitId, newCommitId);
					writer.println("2. In project [" + project + "], running error while processing " +
							"fileId : " + fileId + ", commitId : " + commitId
							+ ", oldCommitId : " + oldCommitId + ", newCommitId : " + newCommitId );
					e.printStackTrace();
					try {
						// If failed, do rollback
						con.rollback();
						//log.error("In project {}, commit is failed, so rollback is worked!", project);
					} catch (SQLException E) {
						E.printStackTrace();
					}
				}

			}

			psTime.close();
			psIns.close();

		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			db.close();
		}
		writer.close();
	}
}
