// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package frc.robot.commands;

import edu.wpi.first.wpilibj2.command.Command;
import frc.robot.subsystems.CoralAlignmentSubSystem;
import frc.robot.subsystems.LEDSubSystem;

/* You should consider using the more terse Command factories API instead https://docs.wpilib.org/en/stable/docs/software/commandbased/organizing-command-based.html#defining-commands */
public class BlinkAlignCommand extends Command {
  LEDSubSystem blink;
  CoralAlignmentSubSystem align;
  /** Creates a new BlinkAlignCommand. */
  public BlinkAlignCommand(LEDSubSystem blink, CoralAlignmentSubSystem align) {
    this.blink = blink;
    this.align = align;
    addRequirements(blink);
    // Use addRequirements() here to declare subsystem dependencies.
  }

  // Called when the command is initially scheduled.
  @Override
  public void initialize() {}

  // Called every time the scheduler runs while the command is scheduled.
  @Override
  public void execute() {
    if(align.isAlignedL4()){
      blink.setColor(blink.SOLID_GREEN);
    }
    else{
      blink.setColor(blink.PARTY_TWINKLE);
    }
  }

  // Called once the command ends or is interrupted.
  @Override
  public void end(boolean interrupted) {}

  // Returns true when the command should end.
  @Override
  public boolean isFinished() {
    return false;
  }
}
