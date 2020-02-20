package refactoringml;

import org.junit.Assert;
import org.junit.Test;

import java.util.Calendar;
import java.util.Random;

public class ProcessMetricTrackerTest {

	@Test
	public void authorOwnership() {
		//TODO: What commit message should I use here?
		ProcessMetricTracker pm = new ProcessMetricTracker("a.Java", "","", "123", Calendar.getInstance());

		for(int i = 0; i < 90; i++) {
			pm.existsIn("commit","Mauricio", 10, 20);
		}

		for(int i = 0; i < 6; i++) {
			pm.existsIn("commit","Diogo", 10, 20);
		}

		for(int i = 0; i < 4; i++) {
			pm.existsIn("commit","Rafael", 10, 20);
		}

		Assert.assertEquals(3, pm.qtyOfAuthors(), 0.0001);
		Assert.assertEquals(100, pm.qtyOfCommits(), 0.0001);
		Assert.assertEquals(0.90, pm.authorOwnership(), 0.0001);
		Assert.assertEquals(2, pm.qtyMajorAuthors());
		Assert.assertEquals(1, pm.qtyMinorAuthors());
		Assert.assertEquals(0, pm.getBugFixCount());
	}

	@Test
	public void countBugFixes() {
		int qtyKeywords = ProcessMetricTracker.bugKeywords.length;
		Random rnd = new Random();

		ProcessMetricTracker pm = new ProcessMetricTracker("a.Java", "","","123", Calendar.getInstance());

		pm.existsIn( "bug fix here","Rafael", 10, 20);

		int qty = 1;
		for(int i = 0; i < 500; i++) {
			String keywordHere = "";
			if(rnd.nextBoolean()) {
				keywordHere = ProcessMetricTracker.bugKeywords[rnd.nextInt(qtyKeywords - 1)];
				qty++;
			}

			pm.existsIn("bla bla " + (keywordHere) + "ble ble","Rafael", 10, 20);
		}

		Assert.assertEquals(qty, pm.getBugFixCount());
	}
}
