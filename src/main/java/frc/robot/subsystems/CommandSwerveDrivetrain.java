// CommandSwerveDrivetrain.java
/*package frc.robot.subsystems;

import com.ctre.phoenix6.swerve.SwerveDrivetrain;
import edu.wpi.first.math.geometry.Pose2d;
import edu.wpi.first.wpilibj2.command.SubsystemBase;

public class CommandSwerveDrivetrain extends SubsystemBase {
    private final SwerveDrivetrain drivetrain;

    public CommandSwerveDrivetrain(SwerveDrivetrain drivetrain) {
        this.drivetrain = drivetrain;
    }

    public void driveFieldRelative(double vx, double vy, double omega) {
        drivetrain.setControl(drivetrain.applyRequest()
            .withVelocityX(vx)
            .withVelocityY(vy)
            .withRotationalRate(omega));
    }

    public void stop() {
        driveFieldRelative(0, 0, 0);
    }

    public Pose2d getPose() {
        return drivetrain.getState().Pose;
    }
}*/

package frc.robot.subsystems;

import static edu.wpi.first.units.Units.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import com.ctre.phoenix6.SignalLogger;
import com.ctre.phoenix6.Utils;
import com.ctre.phoenix6.hardware.TalonFX;
import com.ctre.phoenix6.swerve.SwerveDrivetrainConstants;
import com.ctre.phoenix6.swerve.SwerveModuleConstants;
import com.ctre.phoenix6.swerve.SwerveRequest;
import com.pathplanner.lib.auto.AutoBuilder;
import com.pathplanner.lib.config.PIDConstants;
import com.pathplanner.lib.config.RobotConfig;
import com.pathplanner.lib.controllers.PPHolonomicDriveController;

import choreo.Choreo.TrajectoryLogger;
import choreo.auto.AutoFactory;
import choreo.trajectory.SwerveSample;
import edu.wpi.first.math.MathUtil;
import edu.wpi.first.math.Matrix;
import edu.wpi.first.math.VecBuilder;
import edu.wpi.first.math.controller.PIDController;
import edu.wpi.first.math.estimator.SwerveDrivePoseEstimator;
import edu.wpi.first.math.geometry.Pose2d;
import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.math.kinematics.SwerveModulePosition;
import edu.wpi.first.math.numbers.N1;
import edu.wpi.first.math.numbers.N3;
import edu.wpi.first.math.util.Units;
import edu.wpi.first.networktables.NetworkTable;
import edu.wpi.first.networktables.NetworkTableInstance;
import edu.wpi.first.wpilibj.AnalogInput;
import edu.wpi.first.wpilibj.DriverStation;
import edu.wpi.first.wpilibj.I2C;
import edu.wpi.first.wpilibj.DriverStation.Alliance;
import edu.wpi.first.wpilibj.I2C.Port;
import edu.wpi.first.wpilibj.smartdashboard.SmartDashboard;
import edu.wpi.first.wpilibj.Notifier;
import edu.wpi.first.wpilibj.RobotController;
import edu.wpi.first.wpilibj2.command.Command;
import edu.wpi.first.wpilibj2.command.Commands;
import edu.wpi.first.wpilibj2.command.InstantCommand;
import edu.wpi.first.wpilibj2.command.Subsystem;
import edu.wpi.first.wpilibj2.command.sysid.SysIdRoutine;
import frc.robot.LimelightHelpers;
import frc.robot.generated.TunerConstants.TunerSwerveDrivetrain;
import static frc.robot.LimelightHelpers.*;
import static frc.robot.Constants.AlignmentPoses.*;
import static frc.robot.Constants.ReefPoses.K_CONSTRAINTS_Barging;
/**
 * Class that extends the Phoenix 6 SwerveDrivetrain class and implements
 * Subsystem so it can easily be used in command-based projects.
 */
public class CommandSwerveDrivetrain extends TunerSwerveDrivetrain implements Subsystem {
    private static final double kSimLoopPeriod = 0.005; // 5 ms
    private Notifier m_simNotifier = null;
    private double m_lastSimTime;
    edu.wpi.first.wpilibj.AnalogInput sensor = new AnalogInput(3);
    /* Blue alliance sees forward as 0 degrees (toward red alliance wall) */
    private static final Rotation2d kBlueAlliancePerspectiveRotation = Rotation2d.kZero;
    /* Red alliance sees forward as 180 degrees (toward blue alliance wall) */
    private static final Rotation2d kRedAlliancePerspectiveRotation = Rotation2d.k180deg;
      /** Swerve request to apply during field-centric path following */
    private final SwerveRequest.ApplyFieldSpeeds m_pathApplyFieldSpeeds = new SwerveRequest.ApplyFieldSpeeds();
    private final PIDController m_pathXController = new PIDController(0, 0, 0);
    private final PIDController m_pathYController = new PIDController(0, 0, 0);
    private final PIDController m_pathThetaController = new PIDController(0, 0, 0);
    private Pose2d[] currentAlignmentSide = new Pose2d[]{new Pose2d(0,0, new Rotation2d()), new Pose2d(0,0, new Rotation2d())};
    /* Keep track if we've ever applied the operator perspective before or not */
    private boolean m_hasAppliedOperatorPerspective = false;
    private boolean isAligning = false;
    NetworkTable m_limelightRight = NetworkTableInstance.getDefault().getTable("limelight-fright");
    NetworkTable m_limelightLeft = NetworkTableInstance.getDefault().getTable("limelight-fleft");
    private final SwerveRequest.ApplyRobotSpeeds m_ApplyRobotSpeeds = new SwerveRequest.ApplyRobotSpeeds();
    private boolean needsVisionReset = false;
    private boolean isSkidding = false;
    private boolean isSpinning = false;
    private boolean doRejectUpdate = false;
    private boolean autoScore = false;
    /* Swerve requests to apply during SysId characterization */
    private final SwerveRequest.SysIdSwerveTranslation m_translationCharacterization = new SwerveRequest.SysIdSwerveTranslation();
    private final SwerveRequest.SysIdSwerveSteerGains m_steerCharacterization = new SwerveRequest.SysIdSwerveSteerGains();
    private final SwerveRequest.SysIdSwerveRotation m_rotationCharacterization = new SwerveRequest.SysIdSwerveRotation();
    private final SwerveDrivePoseEstimator pose = new SwerveDrivePoseEstimator(getKinematics(), getPigeon2().getRotation2d(), getModulePositions(), new Pose2d());
    /* SysId routine for characterizing translation. This is used to find PID gains for the drive motors. */
    private final SysIdRoutine m_sysIdRoutineTranslation = new SysIdRoutine(
        new SysIdRoutine.Config(
            null,        // Use default ramp rate (1 V/s)
            Volts.of(4), // Reduce dynamic step voltage to 4 V to prevent brownout
            null,        // Use default timeout (10 s)
            // Log state with SignalLogger class
            state -> SignalLogger.writeString("SysIdTranslation_State", state.toString())
        ),
        new SysIdRoutine.Mechanism(
            output -> setControl(m_translationCharacterization.withVolts(output)),
            null,
            this
        )
    );

    /* SysId routine for characterizing steer. This is used to find PID gains for the steer motors. */
    @SuppressWarnings("unused")
    private final SysIdRoutine m_sysIdRoutineSteer = new SysIdRoutine(
        new SysIdRoutine.Config(
            null,        // Use default ramp rate (1 V/s)
            Volts.of(7), // Use dynamic voltage of 7 V
            null,        // Use default timeout (10 s)
            // Log state with SignalLogger class
            state -> SignalLogger.writeString("SysIdSteer_State", state.toString())
        ),
        new SysIdRoutine.Mechanism(
            volts -> setControl(m_steerCharacterization.withVolts(volts)),
            null,
            this
        )
    );

    /*
     * SysId routine for characterizing rotation.
     * This is used to find PID gains for the FieldCentricFacingAngle HeadingController.
     * See the documentation of SwerveRequest.SysIdSwerveRotation for info on importing the log to SysId.
     */
    @SuppressWarnings("unused")
    private final SysIdRoutine m_sysIdRoutineRotation = new SysIdRoutine(
        new SysIdRoutine.Config(
            /* This is in radians per second², but SysId only supports "volts per second" */
            Volts.of(Math.PI / 6).per(Second),
            /* This is in radians per second, but SysId only supports "volts" */
            Volts.of(Math.PI),
            null, // Use default timeout (10 s)
            // Log state with SignalLogger class
            state -> SignalLogger.writeString("SysIdRotation_State", state.toString())
        ),
        new SysIdRoutine.Mechanism(
            output -> {
                /* output is actually radians per second, but SysId only supports "volts" */
                setControl(m_rotationCharacterization.withRotationalRate(output.in(Volts)));
                /* also log the requested output for SysId */
                SignalLogger.writeDouble("Rotational_Rate", output.in(Volts));
            },
            null,
            this
        )
    );

    /* The SysId routine to test */
    private SysIdRoutine m_sysIdRoutineToApply = m_sysIdRoutineTranslation;
        private boolean otfFollowing;
    
        /**
         * Constructs a CTRE SwerveDrivetrain using the specified constants.
         * <p>
         * This constructs the underlying hardware devices, so users should not construct
         * the devices themselves. If they need the devices, they can access them through
         * getters in the classes.
         *
         * @param drivetrainConstants   Drivetrain-wide constants for the swerve drive
         * @param modules               Constants for each specific module
         */
        public CommandSwerveDrivetrain(
            SwerveDrivetrainConstants drivetrainConstants,
            SwerveModuleConstants<?, ?, ?>... modules
        ) {
            super(drivetrainConstants, modules);
            if (Utils.isSimulation()) {
                startSimThread();
            }
            configureAutoBuilder();
        }
    
        /**
         * Constructs a CTRE SwerveDrivetrain using the specified constants.
         * <p>
         * This constructs the underlying hardware devices, so users should not construct
         * the devices themselves. If they need the devices, they can access them through
         * getters in the classes.
         *
         * @param drivetrainConstants     Drivetrain-wide constants for the swerve drive
         * @param odometryUpdateFrequency The frequency to run the odometry loop. If
         *                                unspecified or set to 0 Hz, this is 250 Hz on
         *                                CAN FD, and 100 Hz on CAN 2.0.
         * @param modules                 Constants for each specific module
         */
        public CommandSwerveDrivetrain(
            SwerveDrivetrainConstants drivetrainConstants,
            double odometryUpdateFrequency,
            SwerveModuleConstants<?, ?, ?>... modules
        ) {
            super(drivetrainConstants, odometryUpdateFrequency, modules);
            if (Utils.isSimulation()) {
                startSimThread();
            }
            configureAutoBuilder();
        }
    
        /**
         * Constructs a CTRE SwerveDrivetrain using the specified constants.
         * <p>
         * This constructs the underlying hardware devices, so users should not construct
         * the devices themselves. If they need the devices, they can access them through
         * getters in the classes.
         *
         * @param drivetrainConstants       Drivetrain-wide constants for the swerve drive
         * @param odometryUpdateFrequency   The frequency to run the odometry loop. If
         *                                  unspecified or set to 0 Hz, this is 250 Hz on
         *                                  CAN FD, and 100 Hz on CAN 2.0.
         * @param odometryStandardDeviation The standard deviation for odometry calculation
         *                                  in the form [x, y, theta]ᵀ, with units in meters
         *                                  and radians
         * @param visionStandardDeviation   The standard deviation for vision calculation
         *                                  in the form [x, y, theta]ᵀ, with units in meters
         *                                  and radians
         * @param modules                   Constants for each specific module
         */
        public CommandSwerveDrivetrain(
            SwerveDrivetrainConstants drivetrainConstants,
            double odometryUpdateFrequency,
            Matrix<N3, N1> odometryStandardDeviation,
            Matrix<N3, N1> visionStandardDeviation,
            SwerveModuleConstants<?, ?, ?>... modules
        ) {
            super(drivetrainConstants, odometryUpdateFrequency, odometryStandardDeviation, visionStandardDeviation, modules);
            if (Utils.isSimulation()) {
                startSimThread();
            }
            configureAutoBuilder();
        }
        /**
     * Creates a new auto factory for this drivetrain.
     *
     * @return AutoFactory for this drivetrain
     */
    public AutoFactory createAutoFactory() {
      return createAutoFactory((sample, isStart) -> {});
  }

  /**
   * Creates a new auto factory for this drivetrain with the given
   * trajectory logger.
   *
   * @param trajLogger Logger for the trajectory
   * @return AutoFactory for this drivetrain
   */
  public AutoFactory createAutoFactory(TrajectoryLogger<SwerveSample> trajLogger) {
      return new AutoFactory(
          () -> getState().Pose,
          this::resetPose,
          this::followPath,
          true,
          this,
          trajLogger
      );
  }

  /**
   * Returns a command that applies the specified control request to this swerve drivetrain.
   *
   * @param request Function returning the request to apply
   * @return Command to run
   */
  public Command applyRequest(Supplier<SwerveRequest> requestSupplier) {
      return run(() -> this.setControl(requestSupplier.get()));
  }

  /**
   * Follows the given field-centric path sample with PID.
   *
   * @param sample Sample along the path to follow
   */
  public void followPath(SwerveSample sample) {
      m_pathThetaController.enableContinuousInput(-Math.PI, Math.PI);

      var pose = getState().Pose;

      var targetSpeeds = sample.getChassisSpeeds();
      targetSpeeds.vxMetersPerSecond += m_pathXController.calculate(
          pose.getX(), sample.x
      );
      targetSpeeds.vyMetersPerSecond += m_pathYController.calculate(
          pose.getY(), sample.y
      );
      targetSpeeds.omegaRadiansPerSecond += m_pathThetaController.calculate(
          pose.getRotation().getRadians(), sample.heading
      );

      setControl(
          m_pathApplyFieldSpeeds.withSpeeds(targetSpeeds)
              .withWheelForceFeedforwardsX(sample.moduleForcesX())
              .withWheelForceFeedforwardsY(sample.moduleForcesY())
      );
  }
       
    
        /**
         * Runs the SysId Quasistatic test in the given direction for the routine
         * specified by {@link #m_sysIdRoutineToApply}.
         *
         * @param direction Direction of the SysId Quasistatic test
         * @return Command to run
         */
        public Command sysIdQuasistatic(SysIdRoutine.Direction direction) {
            return m_sysIdRoutineToApply.quasistatic(direction);
        }
    
        /**
         * Runs the SysId Dynamic test in the given direction for the routine
         * specified by {@link #m_sysIdRoutineToApply}.
         *
         * @param direction Direction of the SysId Dynamic test
         * @return Command to run
         */
        public Command sysIdDynamic(SysIdRoutine.Direction direction) {
            return m_sysIdRoutineToApply.dynamic(direction);
        }
    
        @Override
        public void periodic() {
          pose.update(getPigeon2().getRotation2d(), getModulePositions());
          SmartDashboard.putNumber("null", getState().Pose.getRotation().getDegrees());
          // // SmartDashboard.putBoolean("Range valid", distanceSensor.isRangeValid());
          // // SmartDashboard.putNumber("Distance sensed", getSensorVal());
          
          if (getTVLeft()) {
            var driveState = this.getState();
            double headingDeg = driveState.Pose.getRotation().getDegrees();
            double omegaRps = Units.radiansToRotations(driveState.Speeds.omegaRadiansPerSecond);
      
            LimelightHelpers.SetRobotOrientation("limelight-fleft", getPigeon2().getYaw().getValueAsDouble(), 0, 0, 0, 0, 0);
            var llMeasurement = LimelightHelpers.getBotPoseEstimate_wpiBlue_MegaTag2("limelight-fleft");
            if (llMeasurement != null && llMeasurement.tagCount > 0 && Math.abs(omegaRps) < 2.0) {
              pose.addVisionMeasurement(llMeasurement.pose, llMeasurement.timestampSeconds);
            }
          }
          // if our angular velocity is greater than 360 degrees per second, ignore vision updates
          
          
            var array = new double[] {
                getPose().getX(),
                getPose().getY(),
                getPose().getRotation().getRadians(),
            };
            SmartDashboard.putNumberArray("MyPose", array);
          //   // SmartDashboard.putNumber("Rot", getPose().getRotation().getDegrees());
            /*
             * Periodically try to apply the operator perspective.
             * If we haven't applied the operator perspective before, then we should apply it regardless of DS state.
             * This allows us to correct the perspective in case the robot code restarts mid-match.
             * Otherwise, only check and apply the operator perspective if the DS is disabled.
             * This ensures driving behavior doesn't change until an explicit disable event occurs during testing.
             */
            if (!m_hasAppliedOperatorPerspective || DriverStation.isDisabled()) {
                DriverStation.getAlliance().ifPresent(allianceColor -> {
                    setOperatorPerspectiveForward(
                        allianceColor == Alliance.Red
                            ? kRedAlliancePerspectiveRotation
                            : kBlueAlliancePerspectiveRotation
                    );
                    m_hasAppliedOperatorPerspective = true;
                });
            }
        }
    
        private void startSimThread() {
            m_lastSimTime = Utils.getCurrentTimeSeconds();
    
            /* Run simulation at a faster rate so PID gains behave more reasonably */
            m_simNotifier = new Notifier(() -> {
                final double currentTime = Utils.getCurrentTimeSeconds();
                double deltaTime = currentTime - m_lastSimTime;
                m_lastSimTime = currentTime;
    
                /* use the measured time delta, get battery voltage from WPILib */
                updateSimState(deltaTime, RobotController.getBatteryVoltage());
            });
            m_simNotifier.startPeriodic(kSimLoopPeriod);
        }
    
        /*-------------------------------------------------------------------------------------
          -------------------------------------------------------------------------------------
          -------------------------------------------------------------------------------------
          */
          public SwerveModulePosition[] getModulePositions() {
            return getState().ModulePositions;
          }
          public Pose2d getPose() {
            return pose.getEstimatedPosition();
          }
          public void resetPose(Pose2d rpose) {
            pose.resetPose(rpose);
          }
          public Command driveToPose(Supplier<Pose2d> pose) {
            return AutoBuilder.pathfindToPose(pose.get(), K_CONSTRAINTS_Barging);
          }
          public Pose2d getNearestReefPoseLeft() {
            var currentX = getPose().getX();
            var currentY = getPose().getY();
            Pose2d[][] allPoses = new Pose2d[][] {
              kAliBLUE0_1,
              kAliBLUE2_3, 
              kAliBLUE4_5,
              kAliBLUE6_7,
              kAliBLUE8_9,
              kAliBLUE10_11,
              kAliRED0_1,
              kAliRED2_3,
              kAliRED4_5,
              kAliRED6_7,
              kAliRED8_9,
              kAliRED10_11
            };
            ArrayList<Double> distanceArray = new ArrayList<Double>();
            for (int i=0; i<allPoses.length; i++) {
              distanceArray.add(
                Math.sqrt(
                  Math.pow((currentX - allPoses[i][0].getX()),2)
                  +
                  Math.pow((currentY - allPoses[i][0].getY()),2)
                )
              );
            }
            ArrayList<Double> sortedArray = new ArrayList<>(distanceArray);
            Collections.sort(sortedArray);
            double index = 0;
            for (int i=0; i<sortedArray.size(); i++) {
              if (distanceArray.get(i) == sortedArray.get(0)) {
                index = i;
              }
            }

            Pose2d closest = allPoses[(int)index][0];
            return closest;
          }
          public Pose2d getNearestReefPoseRight() {
            var currentX = getPose().getX();
            var currentY = getPose().getY();
            Pose2d[][] allPoses = new Pose2d[][] {
              kAliBLUE0_1,
              kAliBLUE2_3, 
              kAliBLUE4_5,
              kAliBLUE6_7,
              kAliBLUE8_9,
              kAliBLUE10_11,
              kAliRED0_1,
              kAliRED2_3,
              kAliRED4_5,
              kAliRED6_7,
              kAliRED8_9,
              kAliRED10_11
            };
            ArrayList<Double> distanceArray = new ArrayList<Double>();
            for (int i=0; i<allPoses.length; i++) {
              distanceArray.add(
                Math.sqrt(
                  Math.pow((currentX - allPoses[i][0].getX()),2)
                  +
                  Math.pow((currentY - allPoses[i][0].getY()),2)
                )
              );
            }
            ArrayList<Double> sortedArray = new ArrayList<>(distanceArray);
            Collections.sort(sortedArray);
            double index = 0;
            for (int i=0; i<sortedArray.size(); i++) {
              if (distanceArray.get(i) == sortedArray.get(0)) {
                index = i;
              }
            }

            Pose2d closest = allPoses[(int)index][1];
            return closest;
          }
          public void resetGyro(double angle) {
            getPigeon2().setYaw(angle);
          }
          public void setShouldAutoScore() {
            autoScore = true;
          }
          public void turnOffAutoScore() {
            autoScore = false;
          }
          public boolean getAutoScoreVal() {
            return autoScore;
          }
          public double getTXLeft() {
            return m_limelightLeft.getEntry("tx").getDouble(0.);
          }
          public double getTYLeft() {
            return m_limelightLeft.getEntry("ty").getDouble(0.);
          }
          public void resetPoseBasedOnLL() {
            var llMeasurement = LimelightHelpers.getBotPoseEstimate_wpiBlue_MegaTag2("limelight-left");
            if (llMeasurement != null && llMeasurement.tagCount > 0) {
              pose.resetPose(llMeasurement.pose);
            }
          }
          public boolean getTVLeft() {
            return m_limelightLeft.getEntry("tv").getDouble(0.) == 1.;
          }
          public double getSensorVal() {
            return sensor.getVoltage();
          }
          public double getTIDLeft() {
            return m_limelightLeft.getEntry("tid").getDouble(0);
          }
          public double getTIDRight() {
            return m_limelightRight.getEntry("tid").getDouble(0);
          }
          public Command setFollowingPath() {
            return new InstantCommand(() -> otfFollowing = true);
      }
      public Command stopPathFollowState() {
        return new InstantCommand(() -> otfFollowing = false);
      }
    //   public boolean getTV() {
    //     return m_limelight.getEntry("tv").getDouble(0.0) == 1.0;
    //   }
      public boolean getTVRight() {
        return m_limelightRight.getEntry("tv").getDouble(0.0) == 1.0;
      }
      public void setAligning(boolean aligning) {
        isAligning = aligning;
      }
      public boolean isAligning() {
        return isAligning;
      }
      public Command setAlignmentTarget(Pose2d[] targPose2ds) {
        return new InstantCommand(() -> currentAlignmentSide = targPose2ds);
      }
      public Pose2d getAlignmentTarget(boolean left) {
        // return currentAlignmentSide[left ? 0 : 1];
        return kAliBLUE6_7[0];
      }
    //   public double getTZ() {
    //     return m_limelight.getEntry("ty").getDouble(0.0);
    //   }
    //   public Pose2d getPoseLL() {
    //     var array = m_limelight.getEntry("botpose_wpired").getDoubleArray(new double[]{});
    //     double[] result = {array[0], array[1], array[5]};
    //     Pose2d pose = new Pose2d(result[0], result[1], new Rotation2d(result[2]));
    //     return pose;
    //     // double[] poseArray = {pose.getX(), pose.getY(), ((pose.getRotation().getDegrees())/360)+(pose.getRotation().getDegrees()%360)};
    //     // table.getEntry("RobotPose").setDoubleArray(poseArray);
    //     // SmartDashboard.putNumberArray("Raw Pose", result);
    //   }
      public Pose2d getRightLLPose() {
        @SuppressWarnings("unused")
		    var array = m_limelightRight.getEntry("botpose_wpiblue").getDoubleArray(new double[]{0,0,0,0,0,0});
        // double[] result = {array[0], array[1], array[5]};
        Pose2d pose = new Pose2d(0, 0, new Rotation2d(0));
        return pose;
      }
      public Pose2d getLeftLLPose() {
        var array = m_limelightLeft.getEntry("botpose_wpiblue").getDoubleArray(new double[]{0,0,0,0,0,0});
        double[] result = {array[0], array[1], array[5]};
        Pose2d pose = new Pose2d(result[0], result[1], new Rotation2d(result[2]));
        // return pose;
        return pose;
      }
      public double getTXRight() {
        return m_limelightRight.getEntry("tx").getDouble(0.);
      }
      public double getTYRight() {
        return m_limelightRight.getEntry("ty").getDouble(0.);
      }
      private void configureAutoBuilder() {
        try {
            var config = RobotConfig.fromGUISettings();
            AutoBuilder.configure(() -> getPose(), 
                                this::resetPose,
                                () -> getState().Speeds, 
                                (speeds, feedforwards) -> setControl(
                                    m_ApplyRobotSpeeds.withSpeeds(speeds)
                        .withWheelForceFeedforwardsX(feedforwards.robotRelativeForcesXNewtons())
                        .withWheelForceFeedforwardsY(feedforwards.robotRelativeForcesYNewtons())
                                ), 
                                new PPHolonomicDriveController( // PPHolonomicController is the built in path following controller for holonomic drive trains
                                new PIDConstants(1.75, 0.0, 0.0), // Translation PID constants
                                new PIDConstants(4., 0.0, 0.0)
            ), 
                                config, 
                                () -> {
                                    // Boolean supplier that controls when the path will be mirrored for the red alliance
                                    // This will flip the path being followed to the red side of the field.
                                    // THE ORIGIN WILL REMAIN ON THE BLUE SIDE
                      
                                    var alliance = DriverStation.getAlliance();
                                    if (alliance.isPresent()) {
                                      return alliance.get() == DriverStation.Alliance.Red;
                                    }
                                    return false;
                                  },
                                    this);

                                    
                }
                catch (Exception ex) {
                    DriverStation.reportError("Error", ex.getStackTrace());
                }
        }
    /**
     * Adds a vision measurement to the Kalman Filter. This will correct the odometry pose estimate
     * while still accounting for measurement noise.
     *
     * @param visionRobotPoseMeters The pose of the robot as measured by the vision camera.
     * @param timestampSeconds The timestamp of the vision measurement in seconds.
     */
    @Override
    public void addVisionMeasurement(Pose2d visionRobotPoseMeters, double timestampSeconds) {
        super.addVisionMeasurement(visionRobotPoseMeters, Utils.fpgaToCurrentTime(timestampSeconds));
    }

    /**
     * Adds a vision measurement to the Kalman Filter. This will correct the odometry pose estimate
     * while still accounting for measurement noise.
     * <p>
     * Note that the vision measurement standard deviations passed into this method
     * will continue to apply to future measurements until a subsequent call to
     * {@link #setVisionMeasurementStdDevs(Matrix)} or this method.
     *
     * @param visionRobotPoseMeters The pose of the robot as measured by the vision camera.
     * @param timestampSeconds The timestamp of the vision measurement in seconds.
     * @param visionMeasurementStdDevs Standard deviations of the vision pose measurement
     *     in the form [x, y, theta]ᵀ, with units in meters and radians.
     */
    @Override
    public void addVisionMeasurement(
        Pose2d visionRobotPoseMeters,
        double timestampSeconds,
        Matrix<N3, N1> visionMeasurementStdDevs
    ) {
        super.addVisionMeasurement(visionRobotPoseMeters, Utils.fpgaToCurrentTime(timestampSeconds), visionMeasurementStdDevs);
    }

    public double getTYIntakeLL() {
        return getTYLeft();
    }
    public double getTXIntakeLL() {
        return getTXLeft();
    }
    public boolean getTVIntakeLL() {
        return getTVLeft();
    }
    public void checkForSkid() {
      TalonFX[] driveMotors = new TalonFX[4];
      driveMotors[0] = getModules()[0].getDriveMotor();
      driveMotors[1] = getModules()[1].getDriveMotor();
      driveMotors[2] = getModules()[2].getDriveMotor();
      driveMotors[3] = getModules()[3].getDriveMotor();
      var averageCurrent = 0;
      for (int i=0; i<3; i++) {
        averageCurrent += driveMotors[i].getStatorCurrent().getValueAsDouble();
      }
      averageCurrent /= 4;
      if (averageCurrent > 60) {
        isSkidding = true;
      }
      else {
        isSkidding = false;
      }
    }
}
