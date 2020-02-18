package integration.toyprojects;

import integration.IntegrationBaseTest;
import org.junit.Assert;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import refactoringml.db.RefactoringCommit;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class R1ToyProjectTest extends IntegrationBaseTest {

	@Override
	protected String getRepo() {
		return "https://github.com/jan-gerling/toyrepo-r1.git";
	}

	@Test
	public void refactorings() {
		Assert.assertEquals(3, getRefactoringCommits().size());

		assertRefactoring(getRefactoringCommits(), "e8895b22847c7c54a9e187f9f674db274e6bc103", "Extract Method", 1);

		for (RefactoringCommit refactoringCommit : getRefactoringCommits()){
			Assertions.assertFalse(refactoringCommit.getClassMetrics().isInnerClass());
		}
	}

	@Test
	public void stable() {
		// there are no instances of stable variables, as the repo is too small
		Assert.assertEquals(0, getStableCommits().size());
	}

	@Test
	public void commitMetaData(){
		String commit = "21151bf7e36da52b9305d99755eb6f0b7616e620";
		assertMetaDataRefactoring(
				commit,
				"inline method",
				"Inline Method\tprivate convert(a int) : int inlined to public m1() : void in class a.Example1",
				"@local/repos/toyrepo-r1/" + commit);
	}

	@Test
	public void metrics() {
		// the next two assertions come directly from a 'cloc .' in the project
		Assert.assertEquals(9, project.getJavaLoc());

		Assert.assertEquals(1, project.getNumberOfProductionFiles() + project.getNumberOfTestFiles());

		Assert.assertEquals(1, project.getNumberOfProductionFiles());

		Assert.assertEquals(0, project.getNumberOfTestFiles());

		Assert.assertEquals(9, project.getProductionLoc());
	}
}
