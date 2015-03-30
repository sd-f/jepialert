package at.tugraz;

/**
 * Demo - do whatever you want with this source ;)
 */
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.offbytwo.jenkins.JenkinsServer;
import com.offbytwo.jenkins.model.BuildResult;
import com.offbytwo.jenkins.model.Job;
import com.offbytwo.jenkins.model.JobWithDetails;
import com.pi4j.io.gpio.GpioController;
import com.pi4j.io.gpio.GpioFactory;
import com.pi4j.io.gpio.GpioPinDigitalOutput;
import com.pi4j.io.gpio.PinState;
import com.pi4j.io.gpio.RaspiPin;

/**
 *
 * @author Lucas Reeh <lreeh@tugraz.at>
 */
public class JePiAlert {

  public static void main(String[] args) throws InterruptedException {

    Logger.getLogger(JePiAlert.class.getName()).log(Level.INFO, "started");

    // Initializing GPIO controller and setting initial state of the PINs
    final GpioController gpioController = GpioFactory.getInstance();
    final GpioPinDigitalOutput pin = gpioController.provisionDigitalOutputPin(RaspiPin.GPIO_00, "Alert1", PinState.HIGH);
    final GpioPinDigitalOutput pin2 = gpioController.provisionDigitalOutputPin(RaspiPin.GPIO_01, "Alert2", PinState.HIGH);

    // Registering Jobs to check for status (Your Test-Jobs)
    Logger.getLogger(JePiAlert.class.getName()).log(Level.INFO, "Registering Jobs to check.");
    List<String> jobsToCheck = new ArrayList<>();

    // CHANGEME add your jobs here (Names)
    jobsToCheck.add("Jobname");

    // close GPIO board on kill signal
    Runtime.getRuntime().addShutdownHook(new Thread() {
      @Override
      public void run() {
        shutDown(gpioController, pin, pin2);
      }
    });

    Logger.getLogger(JePiAlert.class.getName()).log(Level.INFO, "GPIO initialized.");

    while (true) {
      try {

        JenkinsServer jenkins = null;
        try {

          // CHANGEME to your host of your jenkins installation
          jenkins = new JenkinsServer(new URI("http://hostofyourjenkins:8080"));
        } catch (URISyntaxException ex) {
          Logger.getLogger(JePiAlert.class.getName()).log(Level.SEVERE, "Could not connect to Jenkins (check URI-Syntax)", ex);
          shutDown(gpioController, pin, pin2);
          throw new RuntimeException("No reason to live on, exiting...");
        }

        if (jenkins == null) {
          Logger.getLogger(JePiAlert.class.getName()).log(Level.SEVERE, "Could not connect to Jenkins");
          throw new RuntimeException("Could not connect to Jenkins (time for debugging)");
        }

        Logger.getLogger(JePiAlert.class.getName()).log(Level.INFO, "JenkinsClient initialized.");

        Logger.getLogger(JePiAlert.class.getName()).log(Level.INFO, "Retrieving Jobs");

        Map<String, Job> jobs = null;

        try {
          jobs = jenkins.getJobs();
        } catch (IOException ex) {
          Logger.getLogger(JePiAlert.class.getName()).log(Level.SEVERE, "Could not retrieve jobs from Jenkins", ex);
          throw new RuntimeException("No reason to live on, exiting...");
        }

        if (jobs == null) {
          Logger.getLogger(JePiAlert.class.getName()).log(Level.SEVERE, "Could not get jobs from Jenkins");
          throw new RuntimeException("Could not get jobs from Jenkins (time for debugging)");
        }

        for (Map.Entry<String, Job> job : jobs.entrySet()) {
          Logger.getLogger(JePiAlert.class.getName()).log(Level.INFO, "Found Job: {0}", job.getValue().getName());
        }

        while (true) {
          Logger.getLogger(JePiAlert.class.getName()).log(Level.INFO, "Checking status...");

          Boolean hasErrors = false;
          Boolean isBuilding = false;

          for (String jobToCheck : jobsToCheck) {
            for (Map.Entry<String, Job> job : jobs.entrySet()) {

              if (jobToCheck.equals(job.getValue().getName())) {

                Logger.getLogger(JePiAlert.class.getName()).log(Level.INFO, "Checking Job: {0}", job.getValue().getName());

                JobWithDetails jobOnJenkins;
                try {
                  jobOnJenkins = job.getValue().details();
                  if (jobOnJenkins == null) {
                    Logger.getLogger(JePiAlert.class.getName()).log(Level.WARNING, "Could not get job details from Jenkins-Job {0}", jobToCheck);
                  } else {
                    // TODO null pointer on .latestBuild --> skip checking
                    if (jobOnJenkins.getLastBuild().details() != null) {
                      Logger.getLogger(JePiAlert.class.getName()).log(Level.INFO, "Job {0} = {1}", new Object[]{jobOnJenkins.getName(), jobOnJenkins.details().getLastBuild().details().getResult().toString()});

                      if (hasErrors(jobOnJenkins)) {
                        hasErrors = true;
                      }

                      if (isBuilding(jobOnJenkins)) {
                        isBuilding = true;
                      }
                    }
                  }
                } catch (IOException ex) {
                  Logger.getLogger(JePiAlert.class.getName()).log(Level.SEVERE, null, ex);
                }
              }
            }
          }

          if (hasErrors) {
            pin.low();
            Logger.getLogger(JePiAlert.class.getName()).log(Level.WARNING, "Warning turned on...");
          } else {
            pin.high();
            Logger.getLogger(JePiAlert.class.getName()).log(Level.INFO, "Warning turned off...");
          }

          if (isBuilding) {
            pin2.low();
            Logger.getLogger(JePiAlert.class.getName()).log(Level.WARNING, "Building turned on...");
          } else {
            pin2.high();
            Logger.getLogger(JePiAlert.class.getName()).log(Level.INFO, "Building turned off...");
          }

          Thread.sleep(20000);
        }
      } catch (RuntimeException | InterruptedException ex) {
        Logger.getLogger(JePiAlert.class.getName()).log(Level.SEVERE, "Error trying again", ex);
        pin.high();
        pin2.high();
        Thread.sleep(30000);
      }
    }

  }

  /**
   * checks whether a job is currently in BUILDING state
   * <p>
   * @param job Jenkins-Job
   * @return true if job is building
   */
  private static Boolean isBuilding(JobWithDetails job) {
    try {
      return job.getLastBuild().details().isBuilding();

    } catch (IOException ex) {
      Logger.getLogger(JePiAlert.class
          .getName()).log(Level.SEVERE, "Could not get Status for Job " + job.getName(), ex);
    }
    return false;
  }

  /**
   * checks whether a job is currently in UNSTABLE (has errors) state
   * <p>
   * @param job Jenkins-Job
   * @return true if job has one of the states UNSTABLE, ABORTED, FAILURE, NOT_BUILT
   */
  private static Boolean hasErrors(JobWithDetails job) {
    try {
      return job.getLastBuild().details().getResult() == BuildResult.UNSTABLE
          || job.getLastBuild().details().getResult() == BuildResult.ABORTED
          || job.getLastBuild().details().getResult() == BuildResult.FAILURE
          || job.getLastBuild().details().getResult() == BuildResult.NOT_BUILT;

    } catch (IOException ex) {
      Logger.getLogger(JePiAlert.class
          .getName()).log(Level.SEVERE, "Could not get Status for Job " + job.getName(), ex);
    }
    return false;
  }

  /**
   * save shutdown for GPIO-controller
   * <p>
   * @param gpioController
   * @param pin
   * @param pin2
   */
  private static void shutDown(GpioController gpioController, GpioPinDigitalOutput pin, GpioPinDigitalOutput pin2) {
    System.out.println("shutting down...");
    pin.high();
    pin2.high();
    gpioController.shutdown();
    System.out.println("GPIO shut down.");
    System.out.println("closed.");
  }

}
