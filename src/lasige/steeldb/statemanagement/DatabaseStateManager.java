/**
Copyright (c) 2007-2013 Alysson Bessani, Eduardo Alchieri, Paulo Sousa, and the authors indicated in the @author tags

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
 */
package lasige.steeldb.statemanagement;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.locks.ReentrantLock;

import bftsmart.tom.core.ExecutionManager;
import bftsmart.consensus.messages.ConsensusMessage;
import bftsmart.reconfiguration.views.View;
import bftsmart.statemanagement.ApplicationState;
import bftsmart.statemanagement.SMMessage;
import bftsmart.statemanagement.strategy.BaseStateManager;
import bftsmart.statemanagement.strategy.StandardSMMessage;
import bftsmart.tom.core.DeliveryThread;
import bftsmart.tom.core.TOMLayer;
import bftsmart.tom.util.TOMUtil;
import org.apache.log4j.Logger;

/**
 * 
 * @author Marcel Santos
 *
 */
public class DatabaseStateManager extends BaseStateManager {

	private int replica;
	private ReentrantLock lockTimer = new ReentrantLock();
	private Timer stateTimer = null;
	private final static long INIT_TIMEOUT = 40000;
	private long timeout = INIT_TIMEOUT;

	//private DeliveryThread dt;
	//private LCManager lcManager;
	private ExecutionManager execManager;

        private Logger logger = Logger.getLogger("steeldb_replica");

	@Override
	public void init(TOMLayer tomLayer, DeliveryThread dt) {
		SVController = tomLayer.controller;

		this.tomLayer = tomLayer;
		this.dt = dt;
		//this.lcManager = tomLayer.getSynchronizer().getLCManager();
		this.execManager = tomLayer.execManager;

		this.replica = 0;

		if (SVController.getCurrentViewN() > 1 && replica == SVController.getStaticConf().getProcessId())
			changeReplica();

		state = null;
		lastCID = -1;
		waitingCID = -1;

		appStateOnly = false;
	}

	private void changeReplica() {
		int pos = -1;
		do {
			pos = this.SVController.getCurrentViewPos(replica);
			replica = this.SVController.getCurrentViewProcesses()[(pos + 1) % SVController.getCurrentViewN()];
		} while (replica == SVController.getStaticConf().getProcessId());
	}

	@Override
	protected void requestState() {
		if (tomLayer.requestsTimer != null)
			tomLayer.requestsTimer.clearAll();

		SMMessage smsg = new StandardSMMessage(SVController.getStaticConf().getProcessId(),
				waitingCID, TOMUtil.SM_REQUEST, replica, null, null, -1, -1);
		tomLayer.getCommunication().send(SVController.getCurrentViewOtherAcceptors(), smsg);

		logger.info("I just sent a request to the other replicas for the state up to EID " + waitingCID);

		TimerTask stateTask =  new TimerTask() {
			public void run() {
				System.out.println("Timeout to retrieve state");
				int[] myself = new int[1];
				myself[0] = SVController.getStaticConf().getProcessId();
				tomLayer.getCommunication().send(myself, new StandardSMMessage(-1, waitingCID, TOMUtil.TRIGGER_SM_LOCALLY, -1, null, null, -1, -1));
			}
		};

		stateTimer = new Timer("state timer");
		timeout = timeout * 2;
		stateTimer.schedule(stateTask,timeout);
	}

	@Override
	public void stateTimeout() {
		lockTimer.lock();
		logger.error("Timeout for the replica that was supposed to send the complete state. Changing desired replica.");
		if (stateTimer != null)
			stateTimer.cancel();
		changeReplica();
		reset();
		requestState();
		lockTimer.unlock();
	}

	@Override
	public void SMRequestDeliver(SMMessage msg, boolean isBFT) {
		if (SVController.getStaticConf().isStateTransferEnabled() && dt.getRecoverer() != null) {
			StandardSMMessage stdMsg = (StandardSMMessage)msg;
			boolean sendState = stdMsg.getReplica() == SVController.getStaticConf().getProcessId();
			logger.info("Received ST request from replica " + stdMsg.getSender() + " to eid " + stdMsg.getCID() + ", sendstate: " + sendState);
			ApplicationState thisState = dt.getRecoverer().getState(msg.getCID(), sendState);
			if (thisState == null) {
				thisState = dt.getRecoverer().getState(-1, sendState);
			}
			int[] targets = { msg.getSender() };
			SMMessage smsg = new StandardSMMessage(SVController.getStaticConf().getProcessId(),
					msg.getCID(), TOMUtil.SM_REPLY, -1, thisState, SVController.getCurrentView(), tomLayer.getSynchronizer().getLCManager().getLastReg(), tomLayer.execManager.getCurrentLeader());
			logger.info("Sending state");
			tomLayer.getCommunication().send(targets, smsg);
			logger.info("Sent");
		}
	}

	@Override
	public void SMReplyDeliver(SMMessage msg, boolean isBFT) {
		lockTimer.lock();
		if (SVController.getStaticConf().isStateTransferEnabled()) {
			if (waitingCID != -1 && msg.getCID() == waitingCID) {
				int currentRegency = -1;
				int currentLeader = -1;
				View currentView = null;

				if (!appStateOnly) {
					senderRegencies.put(msg.getSender(), msg.getRegency());
					senderLeaders.put(msg.getSender(), msg.getLeader());
					senderViews.put(msg.getSender(), msg.getView());
					if (enoughRegencies(msg.getRegency())) currentRegency = msg.getRegency();
					if (enoughLeaders(msg.getLeader())) currentLeader = msg.getLeader();
					if (enoughViews(msg.getView())) {
						currentView = msg.getView();
					}
				} else {
					currentLeader = tomLayer.execManager.getCurrentLeader();
					currentRegency = tomLayer.getSynchronizer().getLCManager().getLastReg();
					currentView = SVController.getCurrentView();
				}

				if (msg.getSender() == replica && msg.getState().getSerializedState() != null) {
					System.out.println("Expected replica sent state. Setting it to state");
					state = msg.getState();
					if (stateTimer != null) stateTimer.cancel();
				}

				senderStates.put(msg.getSender(), msg.getState());

				System.out.println("Verifying more than F replies");
				if (enoughReplies()) {
					System.out.println("More than F confirmed");
					ApplicationState otherReplicaState = getOtherReplicaState();
					System.out.println("State != null: " + (state != null) + ", recvState != null: " + (otherReplicaState != null));
					int haveState = 0;
					if(state != null) {
						haveState = 1;
						//                            byte[] hash = null;
						//                            hash = tomLayer.computeHash(state.getSerializedState());
						//                            if (otherReplicaState != null) {
						//                                if (Arrays.equals(hash, otherReplicaState.getStateHash())) haveState = 1;
						//                                else if (getNumEqualStates() > SVController.getCurrentViewF())
						//                                    haveState = -1;
						//                            }
					}


					System.out.println("appStateOnly: " + appStateOnly + ", view: " + msg.getView());
					System.out.println("otherReplicaState: " + (otherReplicaState != null));
					System.out.println("haveState: " + haveState);
					System.out.println("currentRegency: " + currentRegency);
					System.out.println("currentLeader: " + currentLeader);
					System.out.println("currentView: " + (currentView != null));
					System.out.println("SVController.getCurrentView(): " + SVController.getCurrentView());

					if (otherReplicaState != null && haveState == 1 && currentRegency > -1 &&
							currentLeader > -1 && currentView != null) {

						System.out.println("Received state. Will install it");

						tomLayer.getSynchronizer().getLCManager().setLastReg(currentRegency);
						tomLayer.getSynchronizer().getLCManager().setNextReg(currentRegency);
						tomLayer.getSynchronizer().getLCManager().setNewLeader(currentLeader);
						tomLayer.execManager.setNewLeader(currentLeader);

						dt.deliverLock();
						waitingCID = -1;
						dt.update(state);

						if (!appStateOnly && execManager.stopped()) {
							Queue<ConsensusMessage> stoppedMsgs = execManager.getStoppedMsgs();
							for (ConsensusMessage stopped : stoppedMsgs) {
								if (stopped.getNumber() > state.getLastCID() /*msg.getEid()*/)
									execManager.addOutOfContextMessage(stopped);
							}
							execManager.clearStopped();
							execManager.restart();
						}

						tomLayer.processOutOfContext();

						if (SVController.getCurrentViewId() != currentView.getId()) {
							System.out.println("Installing current view!");
							SVController.reconfigureTo(currentView);
							System.out.println("After install: " + SVController.getCurrentView());
						}

						dt.canDeliver();
						dt.deliverUnlock();

						reset();

						logger.info("I updated the state!");

						tomLayer.requestsTimer.Enabled(true);
						tomLayer.requestsTimer.startTimer();
						if (stateTimer != null) stateTimer.cancel();

						if (appStateOnly) {
							appStateOnly = false;
							tomLayer.getSynchronizer().resumeLC();
						}
					} else if (otherReplicaState == null && (SVController.getCurrentViewN() / 2) < getReplies()) {
						waitingCID = -1;
						reset();

						if (stateTimer != null) stateTimer.cancel();

						if (appStateOnly) {
							requestState();
						}
					} else if (haveState == -1) {
//						logger.info("The replica from which I expected the state, sent one which doesn't match the hash of the others, or it never sent it at all");
						changeReplica();
						reset();
						requestState();

						if (stateTimer != null) stateTimer.cancel();
					}
				}
			}
		}
		lockTimer.unlock();
	}

	/**
	 * Search in the received states table for a state that was not sent by the expected
	 * replica. This is used to compare both states after received the state from expected
	 * and other replicas.
	 * @return The state sent from other replica
	 */
	private ApplicationState getOtherReplicaState() {
		int[] processes = SVController.getCurrentViewProcesses();
		for(int process : processes) {
			if(process == replica)
				continue;
			else {
				ApplicationState otherState = senderStates.get(process);
				if(otherState != null)
					return otherState;
			}
		}
		return null;
	}

	private int getNumEqualStates() {
		List<ApplicationState> states = new ArrayList<ApplicationState>(receivedStates()); 
		int match = 0;
		for (ApplicationState st1 : states) {
			int count = 0;
			for (ApplicationState st2 : states) {
				if(st1 != null && st1.equals(st2))
					count++;
			}
			if(count > match)
				match = count;
		}
		return match;
	}

}
