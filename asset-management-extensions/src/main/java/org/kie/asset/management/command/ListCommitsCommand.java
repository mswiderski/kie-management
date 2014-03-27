package org.kie.asset.management.command;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffEntry.ChangeType;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.io.NullOutputStream;
import org.kie.asset.management.model.CommitInfo;
import org.kie.asset.management.model.FileInfo;
import org.kie.internal.executor.api.CommandContext;
import org.kie.internal.executor.api.ExecutionResults;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ListCommitsCommand extends GitCommand {
	
	private static final Logger logger = LoggerFactory.getLogger(ListCommitsCommand.class);
	// remove dot files from sorted commits per file
	private static final String DEFAULT_FILER_REGEX = ".*\\/\\..*";

    @Override
    public ExecutionResults execute(CommandContext commandContext) throws Exception {

        String gitRepo = (String) getParameter(commandContext, "GitRepository");
        String maxCount = (String) getParameter(commandContext, "MaxCount");
        String branchName = (String) getParameter(commandContext, "BranchName");

        int maxCommits = 10;
        if (maxCount != null) {
        	maxCommits = Integer.parseInt(maxCount);
        }

        Git git = get(gitRepo);

        ObjectId branch = git.getRepository().resolve(Constants.HEAD);
        if (branchName != null) {
            branch = git.getRepository().resolve(branchName);
        }

        Iterable<RevCommit> logs = git.log().add(branch).setMaxCount(maxCommits).call();
        List<CommitInfo> commits = new ArrayList<CommitInfo>();
        for (RevCommit commit : logs) {
            String shortMessage = commit.getShortMessage();
            Date commitDate = new Date(commit.getCommitTime() * 1000L);
            CommitInfo commitInfo = new CommitInfo(commit.getId().getName(), shortMessage, commit.getAuthorIdent().getName(), commitDate);
            commits.add(commitInfo);
            System.out.println(commitInfo);
            commitInfo.setFilesInCommit(getFilesInCommit(git.getRepository(), commit));
        }
        
        Map<String, List<CommitInfo>> commitsPerFile = sortByFileName(commits);

        ExecutionResults results = new ExecutionResults();
        results.setData("Commits", commits);
        results.setData("CommitsPerFile", commitsPerFile);
        return results;
    }
    
    protected Map<String, List<CommitInfo>> sortByFileName(List<CommitInfo> commits) {
    	Map<String, List<CommitInfo>> sorted = new HashMap<String, List<CommitInfo>>();
    	
    	if (commits == null) {
    		return sorted;
    	}
    	
    	for (CommitInfo commit : commits) {
    		List<FileInfo> files = commit.getFilesInCommit();
    		if (files == null) {
    			continue;
    		}
    		
    		for (FileInfo file : files) {
    			if (!file.getName().matches(DEFAULT_FILER_REGEX)) {
	    			List<CommitInfo> commitsPerFile = sorted.get(file.getName());
	    			if (commitsPerFile == null) {
	    				commitsPerFile = new ArrayList<CommitInfo>();
	    				sorted.put(file.getName(), commitsPerFile);
	    			}
	    			
	    			commitsPerFile.add(commit);
    			}
    		}
    	}
    	
    	return sorted;
    }
    
    protected List<FileInfo> getFilesInCommit(Repository repository, RevCommit commit) {
		List<FileInfo> list = new ArrayList<FileInfo>();

		RevWalk rw = new RevWalk(repository);
		try {
			if (commit == null) {
				return list;
			}

			if (commit.getParentCount() == 0) {
				TreeWalk tw = new TreeWalk(repository);
				tw.reset();
				tw.setRecursive(true);
				tw.addTree(commit.getTree());
				while (tw.next()) {
					list.add(FileInfo.build(tw.getPathString(), tw.getPathString(), 0, tw
							.getRawMode(0), tw.getObjectId(0).getName(), commit.getId().getName(),
							ChangeType.ADD.name()));
				}
				tw.release();
			} else {
				RevCommit parent = rw.parseCommit(commit.getParent(0).getId());
				DiffFormatter df = new DiffFormatter(NullOutputStream.INSTANCE);
				df.setRepository(repository);
				df.setDiffComparator(RawTextComparator.DEFAULT);
				df.setDetectRenames(true);
				List<DiffEntry> diffs = df.scan(parent.getTree(), commit.getTree());
				for (DiffEntry diff : diffs) {

					if (diff.getChangeType().equals(ChangeType.DELETE)) {
						list.add(FileInfo.build(diff.getOldPath(), diff.getOldPath(), 0, diff
								.getNewMode().getBits(), diff.getOldId().name(), commit.getId().getName(), diff
								.getChangeType().name()));
					} else if (diff.getChangeType().equals(ChangeType.RENAME)) {
						list.add(FileInfo.build(diff.getOldPath(), diff.getNewPath(), 0, diff
								.getNewMode().getBits(), diff.getOldId().name(), commit.getId().getName(), diff
								.getChangeType().name()));
					} else {
						list.add(FileInfo.build(diff.getNewPath(), diff.getNewPath(), 0, diff
								.getNewMode().getBits(), diff.getOldId().name(), commit.getId().getName(), diff
								.getChangeType().name()));
					}
					
				}
			}
		} catch (Throwable t) {
			logger.error("Unable to determine files in commit due to {} in repository {}", t, repository);
		} finally {
			rw.dispose();
		}
		return list;
	}
}
