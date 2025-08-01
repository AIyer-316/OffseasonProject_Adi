// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package frc.robot.commands;

import edu.wpi.first.wpilibj2.command.InstantCommand;
import edu.wpi.first.wpilibj2.command.ParallelCommandGroup;
import frc.robot.subsystems.ChuteReleaseSubSystem;
import frc.robot.subsystems.WhaleTailReleaseSubSystem;

// NOTE:  Consider using this command inline, rather than writing a subclass.  For more
// information, see:
// https://docs.wpilib.org/en/stable/docs/software/commandbased/convenience-features.html
public class ClimberSetCommand extends ParallelCommandGroup {
  /** Creates a new ClimberSetCommand. */
  public ClimberSetCommand(ChuteReleaseSubSystem chuteServo, WhaleTailReleaseSubSystem whaleServo) {
    // Add your commands in the addCommands() call, e.g.
    // addCommands(new FooCommand(), new BarCommand());
    addCommands(
      new InstantCommand(()-> {whaleServo.setTail();}),
      new InstantCommand(() -> {chuteServo.setChute();})

    );
  }
}
