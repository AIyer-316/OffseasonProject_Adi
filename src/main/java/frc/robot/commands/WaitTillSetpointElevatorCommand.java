// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package frc.robot.commands;

import edu.wpi.first.wpilibj2.command.Command;
import frc.robot.subsystems.ElevatorSubSystem;

/* You should consider using the more terse Command factories API instead https://docs.wpilib.org/en/stable/docs/software/commandbased/organizing-command-based.html#defining-commands */
public class WaitTillSetpointElevatorCommand extends Command {
  public boolean isFinished;
  public ElevatorSubSystem elevator;
  public double setpoint;
  /** Creates a new WaitTillSetpointElevatorCommand. */
  public WaitTillSetpointElevatorCommand(ElevatorSubSystem elevator, double setpoint) {
    // Use addRequirements() here to declare subsystem dependencies.
    this.elevator = elevator;
    this.setpoint = setpoint;
  }

  // Called when the command is initially scheduled.
  @Override
  public void initialize() {
    isFinished = false;
  }

  // Called every time the scheduler runs while the command is scheduled.
  @Override
  public void execute() {
    if(setpoint >= (elevator.getElevatorHeight() - 3) && setpoint <= (elevator.getElevatorHeight() + 3)){
      isFinished = true;
    }
  }

  // Called once the command ends or is interrupted.
  @Override
  public void end(boolean interrupted) {}

  // Returns true when the command should end.
  @Override
  public boolean isFinished() {
    return isFinished;
  }
}
